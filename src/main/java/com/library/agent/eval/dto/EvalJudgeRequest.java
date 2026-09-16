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
}
