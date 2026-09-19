package com.library.agent.healthcheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.config.HealthCheckProperties;
import com.library.agent.config.HealthCheckProperties.Target;
import com.library.agent.config.HealthCheckProperties.Thresholds;
import com.library.agent.context.AgentChatContext;
import com.library.agent.entity.HealthCheckRecord;
import com.library.agent.enums.IntentType;
import com.library.agent.healthcheck.AlertCoverageResolver.AlertDecision;
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
 * 主链路：定时触发 → 代码预取指标并完成阈值判定 → 把指标与判定结论注入 Prompt →
 * 走 ReAct 让模型对异常实例下钻定位根因、调用 {@code sendAlertEmail} 发送告警邮件。
 * 指标由代码预取，模型不必再调工具取数，因此每轮少一次大模型往返；
 * 模型与规则引擎看到的是同一份数据，也不会出现两次采样结果不一致。
 * <p>
 * 可靠性兜底：巡检结束后逐个实例做确定性阈值判定，若存在必须告警的异常但模型本轮
 * 未在邮件中声明覆盖（或压根没发），则代码补发一封告警邮件。
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

        /* 第一步：代码预取各实例指标并完成阈值判定。这一次采集同时供 Prompt 注入与
           兜底判定复用，既省掉模型取数的往返，也保证两边看的是同一份数据 */
        List<InstanceSnapshot> snapshots = new ArrayList<>();
        for (Target target : targets) {
            snapshots.add(collectSnapshot(target));
        }

        /* 第二步：把指标与判定结论注入 Prompt，模型直接从分析与定位根因起步 */
        String prompt = buildPrompt(runId, snapshots);

        /* 第三步：模型对存在异常的实例下钻定位根因并发送告警邮件 */
        String llmSummary = runLlmReAct(runId, prompt);

        /* 第四步：按指标逐项兜底补发模型漏报的异常，并逐实例落库 */
        List<Map<String, Object>> records = new ArrayList<>();
        for (InstanceSnapshot snapshot : snapshots) {
            records.add(persistAndFallback(runId, snapshot, llmSummary));
        }
        sendRegistry.cleanup(runId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("llmSummary", llmSummary);
        result.put("details", records);
        return result;
    }

    /**
     * 单个实例在本轮巡检中的快照：代码预取的指标、规则判定结论与连接错误。
     */
    private record InstanceSnapshot(Target target, Map<String, Object> metrics,
                                    List<Anomaly> anomalies, String connectionError) {
    }

    /**
     * 预取单个实例的指标，并完成阈值判定。
     */
    private InstanceSnapshot collectSnapshot(Target target) {
        Map<String, Object> metrics = collector.collect(target);
        String connectionError = metrics.get("error") == null ? null : String.valueOf(metrics.get("error"));

        /* 连接失败时采集不到任何指标，没有可比对的对象，跳过阈值判定 */
        List<Anomaly> anomalies = connectionError == null
                ? anomalyEvaluator.evaluate(metrics, properties.getThresholds())
                : List.of();
        return new InstanceSnapshot(target, metrics, anomalies, connectionError);
    }

    /**
     * 构建巡检任务 Prompt：注入各实例的实时指标与代码判定结论，并描述可用工具、
     * 执行步骤与输出格式，让模型直接从分析与定位根因起步。
     */
    private String buildPrompt(String runId, List<InstanceSnapshot> snapshots) {
        Thresholds t = properties.getThresholds();
        StringBuilder sb = new StringBuilder();
        sb.append("你是数据库健康巡检 Agent。下列实例的实时指标已由系统采集完成，")
                .append("代码已按阈值完成异常判定。请直接进入分析：对存在异常的实例定位根因并发出告警，")
                .append("不可遗漏任何实例。\n\n");

        sb.append("### 本轮巡检 runId\n").append(runId).append("\n\n");

        sb.append("### 各实例实时指标与判定结论\n");
        for (int i = 0; i < snapshots.size(); i++) {
            InstanceSnapshot snapshot = snapshots.get(i);
            Target target = snapshot.target();
            sb.append("--- 实例 ").append(i + 1).append("：").append(target.getName()).append(" ---\n");
            sb.append("地址: ").append(target.getHost()).append(":").append(target.getPort())
                    .append("/").append(target.getDatabase()).append("\n");
            /* 指标 JSON 沿用健康指标工具原本的输出格式，模型看到的数据结构与以往一致 */
            sb.append("实时指标（已由代码采集，无需再次获取）:\n")
                    .append(collector.toJson(target, snapshot.metrics())).append("\n");
            sb.append("判定结论: ").append(describeAnomalies(snapshot)).append("\n\n");
        }

        sb.append("### 异常判定阈值\n")
                .append("- 缓冲池命中率 bufferHitRate < ").append(t.getBufferHitRateMin()).append("% 异常\n")
                .append("- 锁等待会话数 lockWaitingSessions > ").append(t.getLockWaitingMax()).append(" 异常（必须告警）\n")
                .append("- 事务空闲未关闭连接 idleInTransaction > ").append(t.getIdleInTransactionMax()).append(" 异常（必须告警）\n")
                .append("- 死元组比例 deadTupleRatio > ").append(t.getDeadTupleRatioMax()).append("% 异常\n")
                .append("- 后端写入占比 backendWriteRatio > ").append(t.getBackendWriteRatioMax()).append("% 异常\n")
                .append("- 活跃会话数 activeSessions > ").append(t.getActiveSessionsMax()).append(" 异常\n")
                .append("- 复制延迟: 主库 replicationLagBytes > ").append(t.getReplicationLagBytesMax())
                .append(" 字节；从库 replicationLagSeconds > ").append(t.getReplicationLagSecondsMax()).append(" 秒\n\n");

        sb.append("### 可用工具\n");
        sb.append("1. sendAlertEmail\n")
                .append("   参数: instanceName(实例业务名)、runId(必须原样透传本任务给定的值)、")
                .append("subject(邮件主题)、content(邮件正文)、")
                .append("metrics(可选，本封邮件实际告警的指标英文名列表，取值必须取自该实例指标 JSON 的字段名)\n")
                .append("   说明: 向该实例预配置的告警联系人发送告警邮件。\n\n");
        sb.append("以下为异常下钻工具（只读），用于指标异常时定位根因。它们的 instance 参数既接受 host:port，")
                .append("也接受上面的业务名；使用业务名时可省略 database。\n")
                .append("2. getWaitEventDistribution / listActiveSessions\n")
                .append("   参数: instance、database(可选)\n")
                .append("   说明: 等待事件大类分布、活跃会话明细；用于判断整体卡在锁/IO/客户端等哪一类资源上。\n")
                .append("3. getBlockingChains\n")
                .append("   参数: instance、database(可选)\n")
                .append("   说明: 锁阻塞边列表，含阻塞方 pid/用户/事务已开启时长/持锁对象、被挡住的会话数与是否链源头。\n")
                .append("4. getVacuumAndBloatStatus\n")
                .append("   参数: instance、database(可选)\n")
                .append("   说明: 死元组最多的表，以及持最旧 backend_xmin 的会话与最长事务，用于判别表膨胀根因。\n")
                .append("5. getReplicationStatus\n")
                .append("   参数: instance、database(可选)\n")
                .append("   说明: 主备角色、从库复制延迟分段与 LSN 差值、复制槽保留字节数与非活跃槽位数。\n")
                .append("6. getTableAccessStats\n")
                .append("   参数: instance、database(可选)\n")
                .append("   说明: 顺序扫描最多的表及其索引使用率、热表上未使用的索引。\n")
                .append("7. getTopSlowQueries\n")
                .append("   参数: instance、database(可选)\n")
                .append("   说明: 基于 pg_stat_statements 的 Top 10 慢查询。\n\n");

        sb.append("### 执行步骤\n")
                .append("1. 若上面所有实例的判定结论都是\"未命中任何异常\"，直接输出最终回答，不要调用任何工具。\n")
                .append("2. 对每个存在异常的实例，先调用对应的下钻工具（工具 2-7）定位根因：")
                .append("instance 直接使用该实例的业务名，database 取该实例指标 JSON 里的 database 字段，")
                .append("两者的 argument_sources 标为 EXPLICIT_CURRENT，不需要也不应该向用户索要。\n")
                .append("3. 根因明确后调用 sendAlertEmail 发送告警邮件：instanceName=实例业务名，")
                .append("runId=").append(runId).append("，")
                .append("metrics=本封邮件实际告警的指标英文名列表（取自该实例指标 JSON 的字段名，")
                .append("必须与正文提到的异常指标一致），")
                .append("subject=\"[DB告警] {实例名} {N}项指标异常\"，")
                .append("content=列出异常指标名称、当前值、阈值，并附上下钻得到的根因证据（如阻塞方 pid/持锁时长、")
                .append("死元组最多的表、非活跃槽位名等）与优化建议。\n")
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
        sb.append("要求: tool.name 必须是可用工具之一；arguments 与 argument_sources 必须包含完全相同的参数名，")
                .append("每个参数都要有同名来源标注，取值只能为 EXPLICIT_CURRENT 或 TOOL_OUTPUT")
                .append("（后者表示该值复用此前工具返回结果中的值，用于链式下钻）；")
                .append("填了参数却不给来源标注会导致该次调用被直接拒绝，")
                .append("例如调用 sendAlertEmail 时若填了 metrics，就必须同时给出 metrics 的来源标注；")
                .append("禁止编造指标数据，只能使用上面已给出的指标或工具返回的结果；")
                .append("不要输出 JSON 以外的任何内容。\n");
        return sb.toString();
    }

    /**
     * 把单个实例的判定结论渲染成一句话，注入 Prompt 供模型复核。
     */
    private String describeAnomalies(InstanceSnapshot snapshot) {
        if (snapshot.connectionError() != null) {
            return "实例连接失败，本轮采集不到指标，原因：" + snapshot.connectionError();
        }
        if (snapshot.anomalies().isEmpty()) {
            return "未命中任何异常";
        }

        StringBuilder sb = new StringBuilder("命中 ").append(snapshot.anomalies().size()).append(" 项异常 —— ");
        for (int i = 0; i < snapshot.anomalies().size(); i++) {
            Anomaly anomaly = snapshot.anomalies().get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(anomaly.metric()).append("（当前值 ").append(anomaly.value())
                    .append("，阈值 ").append(anomaly.threshold())
                    .append("，级别 ").append(anomaly.level()).append("）");
        }
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
        /* 自动巡检链路：没有真实用户落字，参数取值来自注入的指标或工具返回，关闭严格落字校验 */
        context.setGroundingEnabled(false);

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
     * 对单个实例执行规则兜底判定、按需补发告警并落库。
     */
    private Map<String, Object> persistAndFallback(String runId, InstanceSnapshot snapshot, String llmSummary) {
        Target target = snapshot.target();
        String status = snapshot.connectionError() != null ? "ERROR"
                : snapshot.anomalies().isEmpty() ? "NORMAL" : "ANOMALY";

        /* 复用第一步预取的指标与规则结论做兜底判定，与模型看到的是同一份数据 */
        AlertDecision decision = AlertCoverageResolver.decide(
                sendRegistry.coveredMetrics(runId, target.getName()),
                snapshot.connectionError(),
                snapshot.anomalies());

        boolean emailSent = sendRegistry.wasAlerted(runId, target.getName());
        if (decision.send()) {
            /* 补发前该实例已经有邮件发出过，说明本次只是补齐模型漏报的项 */
            boolean supplement = emailSent;
            if (sendFallback(target, decision.anomalies(), snapshot.connectionError(), supplement)) {
                emailSent = true;
                /* 只登记本次补发掉的指标，避免把模型已经告警过的项也当作代码补发 */
                sendRegistry.markSent(runId, target.getName(),
                        decision.anomalies().stream().map(Anomaly::metricKey).toList());
            }
        }

        HealthCheckRecord record = new HealthCheckRecord();
        record.setRunId(runId);
        record.setInstanceName(target.getName());
        record.setStatus(status);
        record.setAbnormalMetrics(snapshot.connectionError() == null
                ? toJson(snapshot.anomalies())
                : "{\"connectionError\":\"" + snapshot.connectionError() + "\"}");
        record.setLlmSummary(llmSummary);
        record.setEmailSent(emailSent);
        record.setRecipients(target.getEmails() == null ? null : String.join(",", target.getEmails()));
        recordMapper.insert(record);

        log.info("巡检完成 runId={} instance={} status={} anomalies={} missing={} emailSent={}",
                runId, target.getName(), status, snapshot.anomalies().size(),
                decision.send() ? decision.anomalies().size() : 0, emailSent);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("instance", target.getName());
        detail.put("status", status);
        detail.put("anomalies", snapshot.anomalies());
        detail.put("emailSent", emailSent);
        return detail;
    }

    /**
     * 规则兜底发送告警邮件。
     *
     * @param supplement 是否为补充告警（该实例本轮已发过邮件，本次只补齐漏报的项）
     */
    private boolean sendFallback(Target target, List<Anomaly> anomalies, String connectionError,
                                 boolean supplement) {
        String subject;
        String body;
        if (connectionError != null) {
            subject = "[DB告警] " + target.getName() + " 实例连接失败";
            body = "数据库实例 " + target.getName() + " (" + target.getHost() + ":" + target.getPort()
                    + "/" + target.getDatabase() + ") 无法连接。\n原因: " + connectionError;
        } else {
            subject = "[DB告警] " + target.getName() + (supplement ? " 补充异常指标 " : " 异常指标 ")
                    + anomalies.size() + " 项";
            StringBuilder sb = new StringBuilder(supplement
                    ? "以下异常指标未包含在上一封告警邮件中，现补充告警：\n"
                    : "检测到以下异常指标：\n");
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
