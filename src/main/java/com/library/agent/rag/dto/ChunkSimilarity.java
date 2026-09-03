package com.library.agent.rag.dto;

import lombok.Data;

/**
 * 向量检索命中的分片及其余弦距离（相似度 = 1 - distance）。
 */
@Data
public class ChunkSimilarity {

    private Long chunkId;
    private Double distance;
}
