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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BeirScifactDocRetrievalService {

    private static final int DEFAULT_VECTOR_TOP_K = 1000;
    private static final int DEFAULT_KEYWORD_TOP_K = 1000;
    private static final int DEFAULT_CANDIDATE_TOP_K = 1000;
    private static final int DEFAULT_TOP_K = 100;
    private static final int RRF_K = 60;

    private final LlmService llmService;
    private final ElasticsearchOperations elasticsearchOperations;
    private final JdbcTemplate jdbcTemplate;
    private final TextChunkMapper textChunkMapper;

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
        List<ChunkCandidate> vectorChunks = searchVectorChunks(embedding, vectorTopK);
        List<ChunkCandidate> keywordChunks = searchKeywordChunks(query, keywordTopK);

        List<DocCandidate> docCandidates = mergeDocsByRrf(vectorChunks, keywordChunks, candidateTopK);
        if (Boolean.TRUE.equals(request.getUseRerank())) {
            docCandidates = rerankDocs(query, docCandidates, request);
        }
        List<BeirSearchHit> hits = toHits(docCandidates, topK);
        return new BeirSearchResponse(hits);
    }

    private List<DocCandidate> rerankDocs(String query, List<DocCandidate> candidates, BeirSearchRequest request) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        int rerankN = request.getRerankTopN() == null || request.getRerankTopN() <= 0
                ? candidates.size()
                : request.getRerankTopN();
        int limit = Math.min(rerankN, candidates.size());
        List<DocCandidate> topCandidates = candidates.subList(0, limit);

        List<Long> chunkIds = topCandidates.stream()
                .map(DocCandidate::chunkId)
                .toList();
        Map<Long, String> textById = textChunkMapper.selectByChunkIds(chunkIds).stream()
                .collect(Collectors.toMap(TextChunk::getChunkId, TextChunk::getChunkText));
        List<String> documents = chunkIds.stream()
                .map(id -> textById.getOrDefault(id, ""))
                .toList();

        double minScore = request.getMinRerankScore() == null ? 0.0 : request.getMinRerankScore();
        List<Integer> rerankedIndexes = llmService.rerank(query, documents, limit, minScore);

        List<DocCandidate> reranked = new ArrayList<>();
        for (Integer index : rerankedIndexes) {
            if (index != null && index >= 0 && index < topCandidates.size()) {
                reranked.add(topCandidates.get(index));
            }
        }
        return reranked;
    }

    private int defaultIfInvalid(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    private List<ChunkCandidate> searchVectorChunks(List<Float> embedding, int topK) {
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = embedding.get(i);
        }

        return jdbcTemplate.query(
                "SELECT chunk_id, file_id, chunk_index FROM text_chunk_vector "
                        + "WHERE file_id < 0 ORDER BY embedding <=> ? LIMIT ?",
                ps -> {
                    ps.setObject(1, toPgVector(vector));
                    ps.setInt(2, topK);
                },
                (rs, rowNum) -> new ChunkCandidate(
                        rs.getLong("chunk_id"),
                        rs.getLong("file_id"),
                        rs.getInt("chunk_index"),
                        rowNum + 1
                )
        );
    }

    private List<ChunkCandidate> searchKeywordChunks(String queryText, int topK) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b
                        .must(m -> m.match(mm -> mm.field("chunkText").query(queryText)))
                        .filter(f -> f.wildcard(w -> w.field("fileId").value("-*")))
                ))
                .withPageable(PageRequest.of(0, topK))
                .build();

        List<ChunkCandidate> candidates = new ArrayList<>();
        elasticsearchOperations.search(query, RagChunkDocument.class)
                .forEach(hit -> {
                    RagChunkDocument document = hit.getContent();
                    candidates.add(new ChunkCandidate(
                            Long.valueOf(document.getChunkId()),
                            Long.valueOf(document.getFileId()),
                            document.getChunkIndex(),
                            candidates.size() + 1
                    ));
                });
        return candidates;
    }

    private List<DocCandidate> mergeDocsByRrf(
            List<ChunkCandidate> vectorChunks,
            List<ChunkCandidate> keywordChunks,
            int limit
    ) {
        Map<Long, DocCandidate> docs = new LinkedHashMap<>();
        addDocRrfScores(docs, vectorChunks);
        addDocRrfScores(docs, keywordChunks);

        return docs.values().stream()
                .sorted(Comparator.comparing(DocCandidate::score).reversed())
                .limit(limit)
                .toList();
    }

    private void addDocRrfScores(Map<Long, DocCandidate> docs, List<ChunkCandidate> chunks) {
        Map<Long, ChunkCandidate> firstChunkPerDoc = new LinkedHashMap<>();
        for (ChunkCandidate chunk : chunks) {
            firstChunkPerDoc.putIfAbsent(chunk.fileId(), chunk);
        }

        int docRank = 1;
        for (ChunkCandidate chunk : firstChunkPerDoc.values()) {
            double score = 1.0 / (RRF_K + docRank);
            docs.compute(chunk.fileId(), (fileId, existing) -> {
                if (existing == null) {
                    return new DocCandidate(fileId, chunk.chunkId(), chunk.chunkIndex(), score, chunk.rank());
                }
                ChunkCandidate bestChunk = chunk.rank() < existing.bestChunkRank()
                        ? chunk
                        : new ChunkCandidate(
                        existing.chunkId(),
                        existing.fileId(),
                        existing.chunkIndex(),
                        existing.bestChunkRank()
                );
                return new DocCandidate(
                        fileId,
                        bestChunk.chunkId(),
                        bestChunk.chunkIndex(),
                        existing.score() + score,
                        bestChunk.rank()
                );
            });
            docRank++;
        }
    }

    private List<BeirSearchHit> toHits(List<DocCandidate> docCandidates, int topK) {
        List<BeirSearchHit> hits = new ArrayList<>();
        int rank = 1;

        for (DocCandidate candidate : docCandidates) {
            hits.add(new BeirSearchHit(
                    toBeirDocId(candidate.fileId()),
                    candidate.chunkId(),
                    candidate.fileId(),
                    candidate.chunkIndex(),
                    rank,
                    candidate.score()
            ));
            rank++;

            if (hits.size() >= topK) {
                break;
            }
        }

        return hits;
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

    private String toBeirDocId(Long evalFileId) {
        return String.valueOf((-evalFileId) - 1);
    }

    private record ChunkCandidate(Long chunkId, Long fileId, Integer chunkIndex, int rank) {
    }

    private record DocCandidate(Long fileId, Long chunkId, Integer chunkIndex, double score, int bestChunkRank) {
    }
}
