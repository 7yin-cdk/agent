package com.library.agent.healthcheck;

import com.library.agent.healthcheck.AnomalyEvaluator.Anomaly;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警覆盖判定单测：覆盖"实例级"与"指标级"两种粒度共存的补发规则。
 * <p>
 * 核心契约是两种粒度不能混淆——模型发过邮件但没声明覆盖明细时按实例级整封跳过（防重复告警），
 * 只有模型明确声明了覆盖指标时才逐项算差集（防漏报）。
 */
class AlertCoverageResolverTest {

    private static final Anomaly LOCK_WAITING =
            new Anomaly("锁等待会话数", "lockWaitingSessions", 3, "> 0", "WARNING", "存在锁竞争");
    private static final Anomaly IDLE_IN_TRANSACTION =
            new Anomaly("事务空闲未关闭连接", "idleInTransaction", 2, "> 0", "CRITICAL", "长期占用连接与锁");

    private static final List<Anomaly> TWO_ANOMALIES = List.of(LOCK_WAITING, IDLE_IN_TRANSACTION);

    /** 规则 3：模型本轮没发过邮件，规则判定出的异常项全部补发 */
    @Test
    void sendsAllAnomaliesWhenModelSentNothing() {
        AlertCoverageResolver.AlertDecision decision =
                AlertCoverageResolver.decide(Set.of(), null, TWO_ANOMALIES);

        assertTrue(decision.send());
        assertEquals(TWO_ANOMALIES, decision.anomalies());
    }

    /** 规则 5：模型声明了部分覆盖，只补发它没声明覆盖的那一项 */
    @Test
    void sendsOnlyUncoveredAnomaliesWhenModelDeclaredPartly() {
        AlertCoverageResolver.AlertDecision decision =
                AlertCoverageResolver.decide(Set.of("lockWaitingSessions"), null, TWO_ANOMALIES);

        assertTrue(decision.send());
        assertEquals(List.of(IDLE_IN_TRANSACTION), decision.anomalies());
    }

    /** 规则 5 边界：模型声明的覆盖项已涵盖全部异常，差集为空，不补发 */
    @Test
    void sendsNothingWhenModelDeclaredFullCoverage() {
        AlertCoverageResolver.AlertDecision decision = AlertCoverageResolver.decide(
                Set.of("lockWaitingSessions", "idleInTransaction"), null, TWO_ANOMALIES);

        assertFalse(decision.send());
    }

    /** 规则 4：模型发过邮件但没声明覆盖明细，按实例级整封跳过，避免同一异常收到两封邮件 */
    @Test
    void sendsNothingWhenModelAlertedWithoutDeclaringMetrics() {
        AlertCoverageResolver.AlertDecision decision = AlertCoverageResolver.decide(
                Set.of(EmailSendRegistry.COVERAGE_ALL), null, TWO_ANOMALIES);

        assertFalse(decision.send());
    }

    /** 模型声明了并不存在的指标 key 时，差集退化为全部补发（安全方向：宁可多发不可漏报） */
    @Test
    void sendsAllAnomaliesWhenModelDeclaredUnknownMetrics() {
        AlertCoverageResolver.AlertDecision decision =
                AlertCoverageResolver.decide(Set.of("cpuUsage"), null, TWO_ANOMALIES);

        assertTrue(decision.send());
        assertEquals(TWO_ANOMALIES, decision.anomalies());
    }

    /** 规则 2：指标全部正常，没有任何异常项需要告警 */
    @Test
    void sendsNothingWhenNoAnomaly() {
        AlertCoverageResolver.AlertDecision decision = AlertCoverageResolver.decide(Set.of(), null, List.of());

        assertFalse(decision.send());
    }

    /** 规则 1：连接失败且模型没发过邮件，补发"实例连接失败"告警（anomalies 为空表示这一分支） */
    @Test
    void sendsConnectionAlertWhenConnectionFailedAndModelSentNothing() {
        AlertCoverageResolver.AlertDecision decision =
                AlertCoverageResolver.decide(Set.of(), "指标采集失败: 连接被拒绝", List.of());

        assertTrue(decision.send());
        assertTrue(decision.anomalies().isEmpty());
    }

    /** 规则 1 边界：连接失败但模型已经就该实例发过邮件，不再补发 */
    @Test
    void sendsNothingWhenConnectionFailedButModelAlreadyAlerted() {
        AlertCoverageResolver.AlertDecision decision = AlertCoverageResolver.decide(
                Set.of(EmailSendRegistry.COVERAGE_ALL), "指标采集失败: 连接被拒绝", List.of());

        assertFalse(decision.send());
    }
}
