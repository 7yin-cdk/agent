package com.library.agent.healthcheck;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 告警邮件发送登记表。
 * <p>
 * 记录某轮巡检中每个实例的告警邮件已经覆盖了哪些指标，供规则兜底判断还需要补发哪些项。
 * 登记的是指标明细而不是"这个实例发过邮件"这一件事，因此模型只提到部分指标时，
 * 兜底仍能补发剩下的项，不会因为"已经发过一封"就整体跳过。
 */
@Component
public class EmailSendRegistry {

    /**
     * 未声明覆盖明细时登记的哨兵值。
     * 模型发邮件时没给出指标清单，就无从判断它覆盖了哪些项，此时按实例级语义处理：
     * 视为整封已告警，兜底不再补发。
     */
    public static final String COVERAGE_ALL = "*";

    /** runId → 实例名 → 该实例已覆盖的指标 key 集合（可能只含 COVERAGE_ALL） */
    private final ConcurrentMap<String, ConcurrentMap<String, Set<String>>> coveredByRun =
            new ConcurrentHashMap<>();

    /**
     * 登记某轮巡检中某实例的告警邮件已覆盖的指标。
     */
    public void markSent(String runId, String instanceName, Collection<String> metricKeys) {
        Set<String> covered = coveredByRun
                .computeIfAbsent(runId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(instanceName, k -> ConcurrentHashMap.newKeySet());

        /* 过滤空白项：模型给出[""]这类无效声明时，不能让它进入逐项比对模式 */
        boolean declared = false;
        if (metricKeys != null) {
            for (String metricKey : metricKeys) {
                if (metricKey != null && !metricKey.isBlank()) {
                    covered.add(metricKey.trim());
                    declared = true;
                }
            }
        }
        if (!declared) {
            covered.add(COVERAGE_ALL);
        }
    }

    /**
     * 读取某轮巡检中某实例已覆盖的指标 key；没有记录时返回空集合。
     */
    public Set<String> coveredMetrics(String runId, String instanceName) {
        Map<String, Set<String>> byInstance = coveredByRun.get(runId);
        if (byInstance == null) {
            return Set.of();
        }
        Set<String> covered = byInstance.get(instanceName);
        return covered == null ? Set.of() : Set.copyOf(covered);
    }

    /**
     * 判断某轮巡检中某实例是否已经发过告警邮件，不论模型有没有声明覆盖明细。
     */
    public boolean wasAlerted(String runId, String instanceName) {
        return !coveredMetrics(runId, instanceName).isEmpty();
    }

    /**
     * 巡检结束后清理该轮的登记，避免内存泄漏。
     */
    public void cleanup(String runId) {
        coveredByRun.remove(runId);
    }
}
