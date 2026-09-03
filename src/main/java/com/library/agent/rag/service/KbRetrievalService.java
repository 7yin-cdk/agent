package com.library.agent.rag.service;

import com.library.agent.rag.dto.RetrievedChunk;

import java.util.List;

/**
 * 知识库检索调试服务。
 * <p>
 * 复刻生产 RAG 的多路召回（pgvector + ES 关键词 + RRF），返回结构化命中片段，
 * 用于前端检索调试面板验证召回效果；可选 rerank 与按文件过滤。
 */
public interface KbRetrievalService {

    List<RetrievedChunk> retrieve(String query, Integer topK, Boolean rerank, Long fileId);
}
