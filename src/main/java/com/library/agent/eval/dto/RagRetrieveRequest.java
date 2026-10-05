package com.library.agent.eval.dto;

import lombok.Data;

/**
 * RAG 检索测评的单条请求。
 * <p>
 * 三个检索参数都允许为空，为空时走 {@code KbRetrievalService} 的默认口径；
 * 测评脚本固定传 topK=50 + rerank=true，以拿到尽可能深的重排结果。
 */
@Data
public class RagRetrieveRequest {

    /**
     * 检索 query
     */
    private String query;

    /**
     * 截断长度，内部会 clamp 到 [1, 50]；为空时默认 10
     */
    private Integer topK;

    /**
     * 是否启用 rerank；为空时默认启用
     */
    private Boolean rerank;

    /**
     * 只在该文件内检索；为空时不限文件
     */
    private Long fileId;
}
