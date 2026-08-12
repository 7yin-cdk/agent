package com.library.agent.healthcheck;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 告警邮件发送登记表。
 * <p>
 * 记录某轮巡检中已经由 LLM 工具发送过告警邮件的实例，
 * 供规则兜底判断是否需要补发，避免同一异常重复告警。
 */
@Component
public class EmailSendRegistry {

    private final ConcurrentMap<String, Set<String>> sentByRun = new ConcurrentHashMap<>();

    /**
     * 登记某轮巡检中某实例已发送告警邮件。
     */
    public void markSent(String runId, String instanceName) {
        sentByRun.computeIfAbsent(runId, k -> ConcurrentHashMap.newKeySet()).add(instanceName);
    }

    /**
     * 判断某轮巡检中某实例是否已发送告警邮件。
     */
    public boolean wasSent(String runId, String instanceName) {
        Set<String> sent = sentByRun.get(runId);
        return sent != null && sent.contains(instanceName);
    }

    /**
     * 巡检结束后清理该轮的登记，避免内存泄漏。
     */
    public void cleanup(String runId) {
        sentByRun.remove(runId);
    }
}
