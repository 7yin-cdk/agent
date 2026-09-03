package com.library.agent.rag.dto;

import lombok.Data;

/**
 * 检索调试请求体。
 * <p>
 * topK 默认 10（上限 50）；rerank 默认 true；fileId 可选，指定后仅召回该文档切片。
 */
@Data
public class RetrieveRequest {

    private String query;
    private Integer topK;
    private Boolean rerank;
    private Long fileId;
}
