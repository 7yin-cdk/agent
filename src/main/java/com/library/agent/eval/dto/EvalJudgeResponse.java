package com.library.agent.eval.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 回答质量打分结果。
 */
@Data
public class EvalJudgeResponse {

    /**
     * 六维原始分：correctness / completeness / relevance / actionability / safety / format
     */
    private Map<String, Object> scores;

    /**
     * 六维加权总分（1~5）
     */
    private BigDecimal total;

    /**
     * Judge 给出的评判理由
     */
    private String reason;

    /**
     * 实际使用的 Judge 模型标识，便于追溯
     */
    private String model;

    /**
     * Judge 调用消耗的输入 Token，服务端未返回时为 null
     */
    private Integer inputTokens;

    /**
     * Judge 调用消耗的输出 Token，服务端未返回时为 null
     */
    private Integer outputTokens;
}
