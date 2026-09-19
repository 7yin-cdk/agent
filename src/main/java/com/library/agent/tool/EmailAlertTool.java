package com.library.agent.tool;

import com.library.agent.config.HealthCheckProperties;
import com.library.agent.config.HealthCheckProperties.Target;
import com.library.agent.healthcheck.EmailSendRegistry;
import com.library.agent.mail.MailService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 告警邮件发送工具。
 * <p>
 * 供大模型在 ReAct 循环中调用：按业务名解析目标实例的预配置联系人邮箱，
 * 发送告警邮件。收件人只能来自巡检配置，大模型无法自行指定邮箱地址，
 * 防止误发或乱发。发送成功后把本封邮件覆盖的指标登记到 {@link EmailSendRegistry}，
 * 供规则兜底判断还有哪些异常项需要补发。
 */
@Component
@RequiredArgsConstructor
public class EmailAlertTool {

    private final HealthCheckProperties properties;
    private final MailService mailService;
    private final EmailSendRegistry sendRegistry;

    /**
     * 向指定数据库实例的预配置告警联系人发送告警邮件。
     *
     * @param instanceName 数据库实例业务名，例如：rag库
     * @param runId        本轮巡检的 runId，任务给定，必须原样透传
     * @param subject      邮件主题
     * @param content      邮件正文，需包含异常指标、当前值、阈值与优化建议
     * @param metrics      本封邮件实际告警的指标英文名列表，可为空
     * @return 发送结果 JSON
     */
    @Tool("向指定数据库实例的预配置告警联系人发送告警邮件，收件人由配置决定。邮件正文需包含异常指标、当前值、阈值与优化建议")
    @ToolAccess(ToolAccess.Type.WRITE)
    public String sendAlertEmail(
            @P("数据库实例业务名，在巡检配置中定义，例如：rag库") String instanceName,
            @P("本轮巡检的 runId，必须使用任务给定的值") String runId,
            @P("邮件主题") String subject,
            @P("邮件正文，需包含异常指标、当前值、阈值与优化建议") String content,
            @P(required = false, value = "本封邮件实际告警的指标英文名列表，取值必须是系统注入的指标 JSON "
                    + "里的字段名，例如 [\"lockWaitingSessions\",\"idleInTransaction\"]") List<String> metrics) {

        Target target = findTarget(instanceName);
        if (target == null) {
            return "{\"success\":false,\"error\":\"未找到数据库实例：" + instanceName + "\"}";
        }

        List<String> emails = target.getEmails();
        if (emails == null || emails.isEmpty()) {
            return "{\"success\":false,\"error\":\"实例 " + instanceName + " 未配置告警联系人邮箱\"}";
        }

        boolean sent = mailService.sendHtml(emails, subject, buildHtml(instanceName, content));
        if (sent) {
            /* 登记本封邮件覆盖的指标明细，供规则兜底只补发模型漏报的那些项 */
            sendRegistry.markSent(runId, instanceName, metrics);
            return "{\"success\":true,\"instance\":\"" + instanceName
                    + "\",\"recipients\":" + emails.size() + ",\"message\":\"告警邮件已发送\"}";
        }
        return "{\"success\":false,\"instance\":\"" + instanceName + "\",\"error\":\"邮件发送失败\"}";
    }

    /**
     * 按业务名查找巡检目标实例。
     */
    private Target findTarget(String instanceName) {
        if (properties.getTargets() == null) {
            return null;
        }
        for (Target target : properties.getTargets()) {
            if (target.getName() != null && target.getName().equals(instanceName)) {
                return target;
            }
        }
        return null;
    }

    /**
     * 组装告警邮件 HTML 正文。
     */
    private String buildHtml(String instanceName, String content) {
        return "<html><body style=\"font-family:Microsoft YaHei,Arial,sans-serif;color:#333;\">"
                + "<h2 style=\"color:#c0392b;\">数据库告警：" + instanceName + "</h2>"
                + "<div style=\"white-space:pre-wrap;line-height:1.6;\">"
                + (content == null ? "" : escapeHtml(content))
                + "</div></body></html>";
    }

    /**
     * 转义 HTML 特殊字符，防止告警正文注入。
     */
    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
