package com.library.agent.healthcheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.config.HealthCheckProperties;
import com.library.agent.config.HealthCheckProperties.Target;
import com.library.agent.config.HealthCheckProperties.Thresholds;
import com.library.agent.context.AgentChatContext;
import com.library.agent.entity.HealthCheckRecord;
import com.library.agent.enums.IntentType;
import com.library.agent.healthcheck.AnomalyEvaluator.Anomaly;
import com.library.agent.llm.ToolCallingService;
import com.library.agent.mail.MailService;
import com.library.agent.mapper.HealthCheckRecordMapper;
import com.library.agent.observability.ConversationTraceCollector;
import com.library.agent.observability.ConversationTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库健康巡检定时任务。
 * <p>
 * 主链路：定时触发 → 构造巡检 Prompt → 走 ReAct 让 LLM 调用
 * {@code checkDatabaseHealth} 获取指标、自主判断异常并调用 {@code sendAlertEmail}
 * 发送告警邮件。可靠性兜底：巡检结束后对每个实例做确定性阈值判定，
 * 若存在必须告警的异常但 LLM 本轮未发送邮件，则代码补发一条告警邮件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthCheckRunner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HealthCheckProperties properties;
    private final DatabaseMetricsCollector collector;
    private final ToolCallingService toolCallingService;
    private final MailService mailService;
    private final EmailSendRegistry sendRegistry;
    private final AnomalyEvaluator anomalyEvaluator;
    private final HealthCheckRecordMapper recordMapper;
    private final ConversationTraceService traceService;

    /**
     * 定时巡检入口，按配置的 cron 表达式触发。
     */
    @Scheduled(cron = "${agent.healthcheck.cron}")
    public void scheduledRun() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            runOnce();
        } catch (Exception e) {
            log.error("健康巡检任务执行失败", e);
        }
    }

    /**
     * 执行一轮完整巡检，返回巡检结果摘要。
     */
    public Map<String, Object> runOnce() {
        String runId = "hc_" + System.currentTimeMillis();
        List<Target> targets = properties.getTargets();
        if (targets == null || targets.isEmpty()) {
            return Map.of("runId", runId, "message", "未配置巡检目标 agent.healthcheck.targets");
        }

        String prompt = buildPrompt(runId, targets);
        String llmSummary = runLlmReAct(runId, prompt);

        List<Map<String, Object>> records = new ArrayList<>();
        for (Target target : targets) {
            records.add(persistAndFallback(runId, target, llmSummary));
        }
        sendRegistry.cleanup(runId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("llmSummary", llmSummary);
        result.put("details", records);
        return result;
    }

    /**
     * 构建巡检任务 Prompt，描述待巡检实例、可用工具、阈值标准、执行步骤与输出格式。
     */
    private String buildPrompt(String runId, List<Target> targets) {
        Thresholds t = properties.getThresholds();
        StringBuilder sb = new StringBuilder();
        sb.append("你是数据库健康巡检 Agent。请对以下数据库实例逐一巡检，不可遗漏任何实例。\n\n");

        sb.append("### 待巡检实例列表\n");
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            sb.append(i + 1).append(". 业务名: ").append(target.getName())
                    .append("；地址: ").append(target.getHost()).append(":")
                    .append(target.getPort()).append("/").append(target.getDatabase())
                    .append("\n");
        }
        sb.append("\n### 本轮巡检 runId\n").append(runId).append("\n\n");

        sb.append("### 可用工具\n");
        sb.append("1. checkDatabaseHealth\n")
                .append("   参数: instanceName(数据库实例业务名)\n")
                .append("   说明: 获取指定实例的实时健康指标 JSON，包含 activeSessions、bufferHitRate、")
                .append("lockWaitingSessions、idleInTransaction、deadTupleRatio、backendWriteRatio、")
                .append("replicationRole、replicationLagBytes/replicationLagSeconds。该工具只返回原始数据，不判定异常。\n\n");
        sb.append("2. sendAlertEmail\n")
                .append("   参数: instanceName(实例业务名)、runId(必须原样透传本任务给定的值)、")
                .append("subject(邮件主题)、content(邮件正文)\n")
                .append("   说明: 向该实例预配置的告警联系人发送告警邮件。\n\n");

        sb.append("### 异常判定阈值\n")
                .append("- 缓冲池命中率 bufferHitRate < ").append(t.getBufferHitRateMin()).append("% 异常\n")
                .append("- 锁等待会话数 lockWaitingSessions > ").append(t.getLockWaitingMax()).append(" 异常（必须告警）\n")
                .append("- 事务空闲未关闭连接 idleInTransaction > ").append(t.getIdleInTransactionMax()).append(" 异常（必须告警）\n")
                .append("- 死元组比例 deadTupleRatio > ").append(t.getDeadTupleRatioMax()).append("% 异常\n")
                .append("- 后端写入占比 backendWriteRatio > ").append(t.getBackendWriteRatioMax()).append("% 异常\n")
                .append("- 活跃会话数 activeSessions > ").append(t.getActiveSessionsMax()).append(" 异常\n")
                .append("- 复制延迟: 主库 replicationLagBytes > ").append(t.getReplicationLagBytesMax())
                .append(" 字节；从库 replicationLagSeconds > ").append(t.getReplicationLagSecondsMax()).append(" 秒\n\n");

        sb.append("### 执行步骤\n");
        sb.append("对每个实例依次执行：\n")
                .append("1. 调用 checkDatabaseHealth，参数 instanceName=实例业务名，获取实时指标。\n")
                .append("2. 依据上述阈值判断各项指标是否异常。\n")
                .append("3. 若存在异常指标，调用 sendAlertEmail 发送告警邮件：instanceName=实例业务名，")
                .append("runId=").append(runId).append("，subject=\"[DB告警] {实例名} {N}项指标异常\"，")
                .append("content=列出异常指标名称、当前值、阈值与优化建议。\n")
                .append("4. 记录该实例结论后继续下一个实例。\n\n");

        sb.append("### 历史步骤\n");
        sb.append("{{react_history}}\n");
        sb.append("历史步骤中记录了此前每轮的工具调用与 Observation（工具返回结果），");
        sb.append("必须基于其中的 Observation 继续决策，禁止编造工具返回的数据。\n\n");

        sb.append("### 输出格式\n");
        sb.append("每轮只能输出一个 JSON 对象，使用以下两种形式之一：\n");
        sb.append("调用工具: {\"type\":\"tool\",\"thought\":\"理由\",\"tool\":{\"name\":\"工具名\",")
                .append("\"arguments\":{...},\"argument_sources\":{...}},\"finish\":null}\n");
        sb.append("最终回答: {\"type\":\"finish\",\"thought\":\"理由\",\"tool\":null,")
                .append("\"finish\":{\"answer\":\"最终巡检总结\"}}\n");
        sb.append("要求: tool.name 必须是可用工具之一；arguments 中每个参数都必须有同名的 argument_sources，")
                .append("取值只能为 EXPLICIT_CURRENT；禁止编造指标数据，只能使用工具返回的结果；")
                .append("不要输出 JSON 以外的任何内容。\n");
        return sb.toString();
    }

    /**
     * 执行 LLM 主导的 ReAct 巡检，返回 LLM 的最终总结。
     */
    private String runLlmReAct(String runId, String prompt) {
        AgentChatContext context = new AgentChatContext();
        context.setQuery(prompt);
        context.setConversationId(runId);
        context.setIntentType(IntentType.COMPLEX_TASK);
        context.setHistoryMessages(List.of());

        ConversationTraceCollector trace = new ConversationTraceCollector(null, runId, "scheduled-healthcheck-" + runId);
        boolean llmOk = false;
        String summary;
        try {
            summary = toolCallingService.chatWithTasks(context, prompt, trace);
            llmOk = true;
        } catch (Exception e) {
            log.error("巡检 ReAct 执行失败 runId={}", runId, e);
            summary = "巡检 Agent 执行异常: " + e.getMessage();
        }
        try {
            traceService.save(trace, llmOk ? "SUCCESS" : "ERROR", llmOk ? null : "ReAct 执行异常");
        } catch (Exception e) {
            log.warn("巡检 trace 持久化失败 runId={}", runId, e);
        }
        return summary;
    }

    /**
     * 对单个实例执行规则兜底判定并落库。
     */
    private Map<String, Object> persistAndFallback(String runId, Target target, String llmSummary) {
        Map<String, Object> metrics = collector.collect(target);
        String connectionError = metrics.get("error") == null ? null : String.valueOf(metrics.get("error"));

        List<Anomaly> anomalies = connectionError == null
                ? anomalyEvaluator.evaluate(metrics, properties.getThresholds())
                : List.of();

        String status = connectionError != null ? "ERROR" : anomalies.isEmpty() ? "NORMAL" : "ANOMALY";

        boolean emailSent = sendRegistry.wasSent(runId, target.getName());
        if (status.equals("ERROR") && !emailSent) {
            emailSent = sendFallback(target, List.of(), connectionError);
        } else if (!anomalies.isEmpty() && !emailSent) {
            emailSent = sendFallback(target, anomalies, null);
        }
        if (emailSent) {
            sendRegistry.markSent(runId, target.getName());
        }

        HealthCheckRecord record = new HealthCheckRecord();
        record.setRunId(runId);
        record.setInstanceName(target.getName());
        record.setStatus(status);
        record.setAbnormalMetrics(connectionError == null ? toJson(anomalies) : "{\"connectionError\":\"" + connectionError + "\"}");
        record.setLlmSummary(llmSummary);
        record.setEmailSent(emailSent);
        record.setRecipients(target.getEmails() == null ? null : String.join(",", target.getEmails()));
        recordMapper.insert(record);

        log.info("巡检完成 runId={} instance={} status={} anomalies={} emailSent={}",
                runId, target.getName(), status, anomalies.size(), emailSent);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("instance", target.getName());
        detail.put("status", status);
        detail.put("anomalies", anomalies);
        detail.put("emailSent", emailSent);
        return detail;
    }

    /**
     * 规则兜底发送告警邮件。
     */
    private boolean sendFallback(Target target, List<Anomaly> anomalies, String connectionError) {
        String subject;
        String body;
        if (connectionError != null) {
            subject = "[DB告警] " + target.getName() + " 实例连接失败";
            body = "数据库实例 " + target.getName() + " (" + target.getHost() + ":" + target.getPort()
                    + "/" + target.getDatabase() + ") 无法连接。\n原因: " + connectionError;
        } else {
            subject = "[DB告警] " + target.getName() + " 异常指标 " + anomalies.size() + " 项";
            StringBuilder sb = new StringBuilder("检测到以下异常指标：\n");
            for (Anomaly anomaly : anomalies) {
                sb.append("- ").append(anomaly.metric()).append("：当前值 ").append(anomaly.value())
                        .append("，阈值 ").append(anomaly.threshold()).append("，级别 ").append(anomaly.level())
                        .append("。建议：").append(anomaly.suggestion()).append("\n");
            }
            body = sb.toString();
        }
        String html = "<html><body style=\"font-family:Microsoft YaHei,Arial,sans-serif;color:#333;\">"
                + "<h2 style=\"color:#c0392b;\">数据库告警：" + target.getName() + "</h2>"
                + "<pre style=\"white-space:pre-wrap;line-height:1.6;\">" + escapeHtml(body) + "</pre></body></html>";
        return mailService.sendHtml(target.getEmails(), subject, html);
    }

    /**
     * 将异常项列表序列化为 JSON。
     */
    private String toJson(List<Anomaly> anomalies) {
        try {
            return OBJECT_MAPPER.writeValueAsString(anomalies);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 转义 HTML 特殊字符。
     */
    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
