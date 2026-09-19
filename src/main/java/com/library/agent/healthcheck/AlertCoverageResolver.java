package com.library.agent.healthcheck;

import com.library.agent.healthcheck.AnomalyEvaluator.Anomaly;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 告警补发的覆盖判定器。
 * <p>
 * 巡检结束后，把代码按规则判定出的异常项与"大模型已通过 sendAlertEmail 声明覆盖的指标"做差集，
 * 决定还需要补发哪些告警。判定口径刻意区分两种粒度：
 * 模型没发过邮件、或发过但未声明覆盖明细，都退回实例级语义（与历史行为一致，不重复告警）；
 * 只有模型明确声明了覆盖指标时才进入逐项差集（避免模型少提某项导致该项永不告警）。
 * <p>
 * 本类为无副作用纯逻辑，便于离线单测，不含 Spring/LLM 依赖。
 */
public final class AlertCoverageResolver {

    private AlertCoverageResolver() {
    }

    /**
     * 补发决策。
     *
     * @param send      是否需要补发
     * @param anomalies 需要补发的异常项；send 为 true 且本列表为空时，表示补发"实例连接失败"告警
     */
    public record AlertDecision(boolean send, List<Anomaly> anomalies) {
    }

    private static final AlertDecision NONE = new AlertDecision(false, List.of());

    /**
     * 依据"模型已声明的覆盖范围 + 规则判定结果"得出需要补发的内容。
     *
     * @param covered         模型声明已覆盖的指标 key 集合；为空表示本轮没为该实例发过邮件，
     *                        含 {@link EmailSendRegistry#COVERAGE_ALL} 表示发过但未按指标声明覆盖范围
     * @param connectionError 实例连接失败原因，非空表示本轮采集不到指标
     * @param anomalies       规则判定出的异常项
     * @return 补发决策
     */
    public static AlertDecision decide(Set<String> covered, String connectionError,
                                       List<Anomaly> anomalies) {
        boolean alerted = covered != null && !covered.isEmpty();

        /* 连接失败时采集不到任何指标，没有可逐项比对的对象，沿用实例级语义：
           模型没就该实例发过任何邮件，才补发一条"实例连接失败"告警 */
        if (connectionError != null) {
            return alerted ? NONE : new AlertDecision(true, List.of());
        }

        /* 指标全部正常，没有任何异常项需要告警 */
        if (anomalies == null || anomalies.isEmpty()) {
            return NONE;
        }

        /* 模型本轮没发过邮件：规则判定出的异常项全部需要补发 */
        if (!alerted) {
            return new AlertDecision(true, List.copyOf(anomalies));
        }

        /* 模型发过邮件但没声明覆盖明细（登记的是 COVERAGE_ALL 哨兵）：按实例级处理，
           视为整封已告警。与改造前的行为保持一致，避免同一次异常收到两封邮件 */
        if (covered.contains(EmailSendRegistry.COVERAGE_ALL)) {
            return NONE;
        }

        /* 模型声明了具体覆盖指标：只补发它没有声明覆盖的那些异常项。
           模型若声明了并不存在的指标 key，不会命中任何异常项，差集自然退化为全部补发，
           方向是安全的；反过来也不存在"靠乱填 key 抑制真实告警"的路径 */
        List<Anomaly> missing = new ArrayList<>();
        for (Anomaly anomaly : anomalies) {
            if (!covered.contains(anomaly.metricKey())) {
                missing.add(anomaly);
            }
        }
        return missing.isEmpty() ? NONE : new AlertDecision(true, missing);
    }
}
