package com.library.agent.eval.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 回答质量打分请求。
 */
@Data
public class EvalJudgeRequest {

    /**
     * 用户原始问题
     */
    private String query;

    /**
     * Agent 最终回答
     */
    private String answer;

    /**
     * 参考要点（ground truth key points）；为空时降级为无参考答案评分
     */
    private List<String> keyPoints = new ArrayList<>();

    /**
     * Agent 实际发生的工具调用序列（按发生次序）。
     * <p>
     * 回答正文未必把调用细节写全，缺少本字段时 Judge 只能凭散文推断，
     * 会把“先下钻取证再作答”误判为“未调用工具”。
     */
    private List<EvalJudgeToolCall> toolCalls = new ArrayList<>();
}
