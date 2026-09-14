package com.library.agent.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 测评批次实体类。
 * <p>
 * 对应 PostgreSQL 表 agent_eval_run，一次完整跑批一行，记录该批次被测版本信息与汇总指标；
 * 逐用例判定明细见 {@link EvalCaseResult}。批次号 run_id 唯一，用于回归对比。
 */
@Data
public class EvalRun {

    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 批次号，如 eval_20260914_1530，全局唯一
     */
    private String runId;

    /**
     * 测评套件：CHAT 整体测评 / TOOL 工具调用测评 / RAG 检索测评
     */
    private String suiteName;

    /**
     * 测试集版本，如 v1_20260914
     */
    private String datasetVersion;

    /**
     * 被测 Agent 的 git commit
     */
    private String agentCommit;

    /**
     * 被测模型版本标识
     */
    private String modelVersion;

    /**
     * Judge 模型版本标识（整体测评回答质量打分用）
     */
    private String judgeVersion;

    /**
     * Judge Prompt 版本，与冻结的评分 rubric 一致
     */
    private String judgePromptVersion;

    /**
     * 批次状态：RUNNING 执行中 / FINISHED 已完成 / FAILED 异常中断
     */
    private String status;

    /**
     * 批次开始时间，TIMESTAMPTZ
     */
    private LocalDateTime startedAt;

    /**
     * 批次结束时间，TIMESTAMPTZ
     */
    private LocalDateTime finishedAt;

    /**
     * 用例总数
     */
    private Integer totalCases;

    /**
     * 判定成功的用例数，任务成功率(Task Success Rate)的分子
     */
    private Integer successCases;

    /**
     * 平均 Token / 任务
     */
    private BigDecimal avgTokens;

    /**
     * Token P95
     */
    private BigDecimal p95Tokens;

    /**
     * 平均端到端耗时（毫秒）
     */
    private BigDecimal avgDurationMs;

    /**
     * 耗时 P95（毫秒）
     */
    private BigDecimal p95DurationMs;

    /**
     * 回答质量加权均分（1~5）
     */
    private BigDecimal avgQualityScore;

    /**
     * 全量指标快照（分层/分组聚合结果），对应 JSONB
     */
    private Map<String, Object> summaryJson;

    /**
     * 创建时间，TIMESTAMPTZ
     */
    private LocalDateTime createdAt;
}
