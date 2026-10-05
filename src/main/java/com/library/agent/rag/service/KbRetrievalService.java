package com.library.agent.rag.service;

import com.library.agent.rag.dto.RetrievedChunk;
import com.library.agent.rag.dto.RetrievalStageTrace;

import java.util.List;

/**
 * 知识库检索调试服务。
 * <p>
 * 复刻生产 RAG 的多路召回（pgvector + ES 关键词 + RRF），返回结构化命中片段，
 * 用于前端检索调试面板验证召回效果；可选 rerank 与按文件过滤。
 */
public interface KbRetrievalService {

    List<RetrievedChunk> retrieve(String query, Integer topK, Boolean rerank, Long fileId);

    /**
     * 在 4 参版本基础上额外回填各阶段的有序 chunkId 快照，供测评 harness 计算召回指标。
     *
     * @param stageTrace 收集容器，可为 null；为 null 时行为与 4 参版本完全一致
     */
    List<RetrievedChunk> retrieve(String query, Integer topK, Boolean rerank, Long fileId,
                                  RetrievalStageTrace stageTrace);
}
