package com.library.agent.eval.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 单用例评测结果上报请求。
 * <p>
 * Task Success / 质量分由评测脚本判定后随请求提交；Token 与耗时取自 trace_id 关联的可观测数据。
 */
@Data
public class EvalCaseResultRequest {

    /**
     * 用例编号，如 chat_001
     */
    private String caseId;

    /**
     * 用例分类
     */
    private String category;

    /**
     * 用例难度：EASY / MEDIUM / HARD
     */
    private String difficulty;

    /**
     * 用户输入原文
     */
    private String query;

    /**
     * 关联可观测 trace 的 traceId
     */
    private String traceId;

    /**
     * 任务是否成功
     */
    private Boolean taskSuccess;

    /**
     * 成功/失败判定理由
     */
    private String successReason;

    /**
     * 回答质量六维原始分
     */
    private Map<String, Object> qualityScores;

    /**
     * 回答质量加权总分
     */
    private BigDecimal qualityTotal;

    /**
     * 工具选择明细
     */
    private Map<String, Object> toolSelection;

    /**
     * 参数校验明细
     */
    private Map<String, Object> paramResult;

    /**
     * 检索阶段明细
     */
    private Map<String, Object> retrievalResult;

    /**
     * Judge 判定理由
     */
    private String judgeReason;

    /**
     * 人工抽检评分
     */
    private Map<String, Object> humanScores;

    /**
     * 是否已人工复核
     */
    private Boolean humanChecked;

    /**
     * 输入 Token 合计
     */
    private Integer totalInputTokens;

    /**
     * 输出 Token 合计
     */
    private Integer totalOutputTokens;

    /**
     * 总 Token
     */
    private Integer totalTokens;

    /**
     * 端到端耗时（毫秒）
     */
    private Integer durationMs;

    /**
     * LLM 调用次数
     */
    private Integer llmCallCount;

    /**
     * 工具调用次数
     */
    private Integer toolCallCount;

    /**
     * Agent 最终输出原文
     */
    private String rawOutput;

    /**
     * 异常信息
     */
    private String errorMessage;
}
