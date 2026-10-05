package com.library.agent.eval.dto;

import com.library.agent.rag.dto.RetrievalStageTrace;
import com.library.agent.rag.dto.RetrievedChunk;
import lombok.Data;

import java.util.List;

/**
 * RAG 检索测评的单条响应。
 * <p>
 * 一次请求同时带回最终命中结果（hits）与各阶段的有序 chunkId 快照（stage），
 * 评测脚本据此计算双路召回率、rerank 前后排名与整体召回率，无需再发一次 retrieve。
 */
@Data
public class RagRetrieveResponse {

    /**
     * 本次检索的 query，原样回显便于批量结果对齐
     */
    private String query;

    /**
     * 最终命中片段，已按输出顺序排列
     */
    private List<RetrievedChunk> hits;

    /**
     * 各阶段有序 chunkId 快照
     */
    private RetrievalStageTrace stage;
}
