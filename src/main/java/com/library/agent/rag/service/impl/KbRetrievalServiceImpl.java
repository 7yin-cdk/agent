package com.library.agent.rag.service.impl;

import com.library.agent.es.service.KeywordSearchService;
import com.library.agent.llm.LlmService;
import com.library.agent.mapper.TextChunkMapper;
import com.library.agent.mapper.TextChunkVectorMapper;
import com.library.agent.rag.dto.ChunkSimilarity;
import com.library.agent.rag.dto.ChunkView;
import com.library.agent.rag.dto.RetrievedChunk;
import com.library.agent.rag.service.KbRetrievalService;
import com.library.agent.rag.service.RrfMerger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 知识库检索调试实现，召回管线与生产 RAG（RagServiceImpl.buildRagPrompt）保持一致。
 */
@Service
@RequiredArgsConstructor
public class KbRetrievalServiceImpl implements KbRetrievalService {

    private static final int VECTOR_TOP_K = 100;
    private static final int KEYWORD_TOP_K = 100;
    private static final int CANDIDATE_LIMIT = 80;

    private final LlmService llmService;
    private final TextChunkVectorMapper textChunkVectorMapper;
    private final KeywordSearchService keywordSearchService;
    private final TextChunkMapper textChunkMapper;

    @Override
    public List<RetrievedChunk> retrieve(String query, Integer topK, Boolean rerank, Long fileId) {
        int limit = topK == null ? 10 : Math.min(Math.max(topK, 1), 50);
        boolean doRerank = rerank == null || rerank;

        /* 1. 向量检索（带距离） + 关键词检索 */
        float[] vector = toVector(llmService.embed(query.trim()));
        List<ChunkSimilarity> similarities = textChunkVectorMapper.selectTopKWithDistance(vector, VECTOR_TOP_K);
        List<Long> keywordIds = keywordSearchService.searchChunkIds(query.trim(), KEYWORD_TOP_K);
        List<Long> vectorIds = similarities.stream().map(ChunkSimilarity::getChunkId).toList();

        if (vectorIds.isEmpty() && keywordIds.isEmpty()) {
            return List.of();
        }

        /* 2. RRF 融合候选 */
        List<Long> merged = RrfMerger.merge(vectorIds, keywordIds, CANDIDATE_LIMIT);
        List<ChunkView> rows = textChunkMapper.selectByIdsWithFile(merged);
        if (fileId != null) {
            rows = rows.stream().filter(r -> Objects.equals(r.getFileId(), fileId)).toList();
        }
        if (rows.isEmpty()) {
            return List.of();
        }

        /* 3. 可选 rerank 重排：返回在原候选列表中的下标 */
        if (doRerank) {
            List<String> texts = rows.stream().map(ChunkView::getChunkText).toList();
            List<Integer> chosen = llmService.rerank(query.trim(), texts, Math.min(limit, texts.size()), 0.0);
            List<ChunkView> reordered = new ArrayList<>();
            for (Integer idx : chosen) {
                reordered.add(rows.get(idx));
            }
            rows = reordered;
        }

        /* 4. 组装结果：score 取向量余弦相似度 1-distance */
        Map<Long, Double> scoreByChunk = new HashMap<>();
        for (ChunkSimilarity s : similarities) {
            if (s.getDistance() != null) {
                scoreByChunk.put(s.getChunkId(), 1.0 - s.getDistance());
            }
        }
        List<RetrievedChunk> result = new ArrayList<>();
        for (int i = 0; i < rows.size() && i < limit; i++) {
            ChunkView r = rows.get(i);
            RetrievedChunk hit = new RetrievedChunk();
            hit.setFileId(r.getFileId());
            hit.setFileName(r.getFileName());
            hit.setChunkId(r.getChunkId());
            hit.setChunkIndex(r.getChunkIndex());
            hit.setChunkText(r.getChunkText());
            hit.setScore(scoreByChunk.get(r.getChunkId()));
            hit.setRank(i + 1);
            result.add(hit);
        }
        return result;
    }

    private float[] toVector(List<Float> embed) {
        float[] vector = new float[embed.size()];
        for (int i = 0; i < embed.size(); i++) {
            vector[i] = embed.get(i);
        }
        return vector;
    }
}
