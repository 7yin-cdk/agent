package com.library.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据库健康巡检记录实体。
 * <p>
 * 对应 PostgreSQL 表 health_check_record，每轮巡检对每个实例写入一条，
 * 记录异常指标、LLM 巡检结论与告警邮件发送情况，供审计与后续分析。
 */
@Data
public class HealthCheckRecord {

    /**
     * 记录 ID。
     */
    private Long id;

    /**
     * 巡检轮次 ID。
     */
    private String runId;

    /**
     * 实例业务名。
     */
    private String instanceName;

    /**
     * 巡检状态：NORMAL 正常 / ANOMALY 异常 / ERROR 采集失败。
     */
    private String status;

    /**
     * 异常指标明细（JSON 数组）。
     */
    private String abnormalMetrics;

    /**
     * LLM 巡检总结。
     */
    private String llmSummary;

    /**
     * 是否已发送告警邮件。
     */
    private Boolean emailSent;

    /**
     * 告警收件人（逗号分隔）。
     */
    private String recipients;

    /**
     * 巡检时间。
     */
    private LocalDateTime checkedAt;

    /**
     * 创建时间。
     */
    private LocalDateTime createdAt;
}
