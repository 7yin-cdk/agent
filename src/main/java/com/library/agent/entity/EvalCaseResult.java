package com.library.agent.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 评测单用例结果实体类。
 * <p>
 * 对应 PostgreSQL 表 agent_eval_case_result，一行一用例，承载整体测评、工具调用测评、
 * RAG 检索测评三大模块的判定明细；Token 与耗时取自 trace_id 关联的可观测三表。
 */
@Data
public class EvalCaseResult {

    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 所属批次号，关联 agent_eval_run.run_id
     */
    private String runId;

    /**
     * 用例编号，如 chat_001 / tool_042 / rag_zh_003
     */
    private String caseId;

    /**
     * 用例分类：知识问答/诊断/告警/越界，或 single_tool/multi_tool/hard_negative 等
     */
    private String category;

    /**
     * 用例难度：EASY / MEDIUM / HARD
     */
    private String difficulty;

    /**
     * 用户输入原文，便于失败回溯
     */
    private String query;

    /**
     * 关联可观测三表 agent_conversation_trace / _llm_call_record / _tool_call_record
     */
    private String traceId;

    /**
     * 任务是否成功，整体测评任务成功率的分子
     */
    private Boolean taskSuccess;

    /**
     * 成功/失败判定理由（规则命中项或 Judge 结论）
     */
    private String successReason;

    /**
     * 回答质量六维原始分，如 {"correctness":5,"completeness":4,...}，对应 JSONB
     */
    private Map<String, Object> qualityScores;

    /**
     * 回答质量加权总分（1~5），便于聚合排序
     */
    private BigDecimal qualityTotal;

    /**
     * 工具选择明细 {"expected":[],"actual":[],"precision":..,"recall":..,"f1":..,"overcall":..}，对应 JSONB
     */
    private Map<String, Object> toolSelection;

    /**
     * 参数校验明细 {"count_ok":..,"type_ok":..,"value_ok":..,"grounded":..,"hallucination":..}，对应 JSONB
     */
    private Map<String, Object> paramResult;

    /**
     * 检索阶段明细 {"vector_hit":..,"keyword_hit":..,"rrf_rank":..,"rerank_rank":..}，对应 JSONB
     */
    private Map<String, Object> retrievalResult;

    /**
     * Judge 判定理由原文
     */
    private String judgeReason;

    /**
     * 人工抽检评分（校准 Judge 一致性用），对应 JSONB
     */
    private Map<String, Object> humanScores;

    /**
     * 是否已人工复核
     */
    private Boolean humanChecked;

    /**
     * 输入 Token 合计（取自 trace）
     */
    private Integer totalInputTokens;

    /**
     * 输出 Token 合计（取自 trace）
     */
    private Integer totalOutputTokens;

    /**
     * 总 Token（输入 + 输出）
     */
    private Integer totalTokens;

    /**
     * 端到端耗时（毫秒）
     */
    private Integer durationMs;

    /**
     * LLM 调用次数，衡量路由/ReAct 开销
     */
    private Integer llmCallCount;

    /**
     * 工具调用次数
     */
    private Integer toolCallCount;

    /**
     * Agent 最终输出原文，回答质量打分依据
     */
    private String rawOutput;

    /**
     * 异常信息（trace.status 非成功时）
     */
    private String errorMessage;

    /**
     * 创建时间，TIMESTAMPTZ
     */
    private LocalDateTime createdAt;
}
