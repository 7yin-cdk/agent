package com.library.agent.beir;

import com.library.agent.beir.dto.BeirSearchHit;
import com.library.agent.beir.dto.BeirSearchRequest;
import com.library.agent.beir.dto.BeirSearchResponse;
import com.library.agent.entity.RagChunkDocument;
import com.library.agent.entity.TextChunk;
import com.library.agent.llm.LlmService;
import com.library.agent.mapper.TextChunkMapper;
import lombok.RequiredArgsConstructor;
import org.postgresql.util.PGobject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BeirScifactRetrievalService {

    private static final int DEFAULT_VECTOR_TOP_K = 20;
    private static final int DEFAULT_KEYWORD_TOP_K = 20;
    private static final int DEFAULT_CANDIDATE_TOP_K = 10;
    private static final int DEFAULT_TOP_K = 10;
    private static final int RRF_K = 60;

    private final LlmService llmService;
    private final TextChunkMapper textChunkMapper;
    private final ElasticsearchOperations elasticsearchOperations;
    private final JdbcTemplate jdbcTemplate;

    public BeirSearchResponse search(BeirSearchRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            throw new IllegalArgumentException("query cannot be empty");
        }

        String query = request.getQuery().trim();
        int vectorTopK = defaultIfInvalid(request.getVectorTopK(), DEFAULT_VECTOR_TOP_K);
        int keywordTopK = defaultIfInvalid(request.getKeywordTopK(), DEFAULT_KEYWORD_TOP_K);
        int candidateTopK = defaultIfInvalid(request.getCandidateTopK(), DEFAULT_CANDIDATE_TOP_K);
        int topK = defaultIfInvalid(request.getTopK(), DEFAULT_TOP_K);

        List<Float> embedding = llmService.embed(query);
        List<Long> vectorIds = searchVectorChunkIds(embedding, vectorTopK);
        List<Long> keywordIds = searchKeywordChunkIds(query, keywordTopK);
        List<Long> candidateIds = mergeByRrf(vectorIds, keywordIds, candidateTopK);

        if (Boolean.TRUE.equals(request.getUseRerank())) {
            candidateIds = rerank(query, candidateIds, request);
        }

        List<BeirSearchHit> hits = toDocumentHits(candidateIds, topK);
        return new BeirSearchResponse(hits);
    }

    private int defaultIfInvalid(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    private List<Long> searchVectorChunkIds(List<Float> embedding, int topK) {
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = embedding.get(i);
        }

        return jdbcTemplate.query(
                "SELECT chunk_id FROM text_chunk_vector WHERE file_id < 0 ORDER BY embedding <=> ? LIMIT ?",
                ps -> {
                    ps.setObject(1, toPgVector(vector));
                    ps.setInt(2, topK);
                },
                (rs, rowNum) -> rs.getLong("chunk_id")
        );
    }

    private PGobject toPgVector(float[] vector) {
        try {
            PGobject pgObject = new PGobject();
            pgObject.setType("vector");
            pgObject.setValue(toVectorLiteral(vector));
            return pgObject;
        } catch (Exception e) {
            throw new RuntimeException("Build pgvector parameter failed", e);
        }
    }

    private String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 12);
        builder.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        builder.append(']');
        return builder.toString();
    }

    private List<Long> searchKeywordChunkIds(String queryText, int topK) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b
                        .must(m -> m.match(mm -> mm.field("chunkText").query(queryText)))
                        .filter(f -> f.wildcard(w -> w.field("fileId").value("-*")))
                ))
                .withPageable(PageRequest.of(0, topK))
                .build();

        return elasticsearchOperations.search(query, RagChunkDocument.class)
                .stream()
                .map(hit -> Long.valueOf(hit.getContent().getChunkId()))
                .toList();
    }

    private List<Long> mergeByRrf(List<Long> vectorIds, List<Long> keywordIds, int limit) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        addRrfScores(scores, vectorIds);
        addRrfScores(scores, keywordIds);

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private void addRrfScores(Map<Long, Double> scores, List<Long> ids) {
        for (int i = 0; i < ids.size(); i++) {
            scores.merge(ids.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }

    private List<Long> rerank(String query, List<Long> candidateIds, BeirSearchRequest request) {
        if (candidateIds.isEmpty()) {
            return candidateIds;
        }

        List<TextChunk> chunks = textChunkMapper.selectByChunkIds(candidateIds);
        List<String> documents = chunks.stream()
                .map(TextChunk::getChunkText)
                .toList();

        int topN = defaultIfInvalid(request.getRerankTopN(), documents.size());
        double minScore = request.getMinRerankScore() == null ? 0.0 : request.getMinRerankScore();
        List<Integer> rerankedIndexes = llmService.rerank(query, documents, topN, minScore);

        List<Long> reranked = new ArrayList<>();
        for (Integer index : rerankedIndexes) {
            if (index != null && index >= 0 && index < chunks.size()) {
                reranked.add(chunks.get(index).getChunkId());
            }
        }
        return reranked;
    }

    private List<BeirSearchHit> toDocumentHits(List<Long> chunkIds, int topK) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }

        List<TextChunk> chunks = textChunkMapper.selectByChunkIds(chunkIds);
        List<BeirSearchHit> hits = new ArrayList<>();
        LinkedHashSet<String> seenDocIds = new LinkedHashSet<>();
        int rank = 1;

        for (TextChunk chunk : chunks) {
            Long fileId = chunk.getFileId();
            if (fileId == null || fileId >= 0) {
                continue;
            }

            String beirDocId = toBeirDocId(fileId);
            if (!seenDocIds.add(beirDocId)) {
                continue;
            }

            hits.add(new BeirSearchHit(
                    beirDocId,
                    chunk.getChunkId(),
                    fileId,
                    chunk.getChunkIndex(),
                    rank,
                    1.0 / rank
            ));
            rank++;

            if (hits.size() >= topK) {
                break;
            }
        }

        return hits;
    }

    private String toBeirDocId(Long evalFileId) {
        return String.valueOf((-evalFileId) - 1);
    }
}
