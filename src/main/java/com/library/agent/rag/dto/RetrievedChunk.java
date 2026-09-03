package com.library.agent.rag.dto;

import lombok.Data;

/**
 * 检索调试结果片段。
 * <p>
 * score 为向量余弦相似度（1 - distance）；纯关键词命中（无向量距离）时为 null。
 * rank 为最终输出顺序（从 1 开始），受可选 rerank 影响。
 */
@Data
public class RetrievedChunk {

    private Long fileId;
    private String fileName;
    private Long chunkId;
    private Integer chunkIndex;
    private String chunkText;
    private Double score;
    private Integer rank;
}
