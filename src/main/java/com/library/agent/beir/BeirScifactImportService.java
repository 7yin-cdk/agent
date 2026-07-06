package com.library.agent.beir;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.beir.dto.BeirCorpusDocument;
import com.library.agent.beir.dto.BeirImportResponse;
import com.library.agent.entity.TextChunk;
import com.library.agent.entity.TextChunkVector;
import com.library.agent.es.service.KeywordSearchService;
import com.library.agent.llm.LlmService;
import com.library.agent.mapper.TextChunkMapper;
import com.library.agent.mapper.TextChunkVectorMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BeirScifactImportService {

    private static final int MAX_CHUNK_SIZE = 800;
    private static final int OVERLAP_SIZE = 100;
    private static final String[] SPLIT_SYMBOLS = {
            "\n", "。", "；", "，", ". ", ", "
    };

    private final ObjectMapper objectMapper;
    private final LlmService llmService;
    private final TextChunkMapper textChunkMapper;
    private final TextChunkVectorMapper textChunkVectorMapper;
    private final KeywordSearchService keywordSearchService;
    private final JdbcTemplate jdbcTemplate;

    public BeirImportResponse importJsonl(String corpusJsonlPath, Integer limit) {
        if (corpusJsonlPath == null || corpusJsonlPath.isBlank()) {
            throw new IllegalArgumentException("corpusJsonlPath cannot be empty");
        }

        Path path = Path.of(corpusJsonlPath);
        int total = 0;
        int imported = 0;
        int skipped = 0;
        int safeLimit = limit == null || limit <= 0 ? Integer.MAX_VALUE : limit;

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null && total < safeLimit) {
                if (line.isBlank()) {
                    continue;
                }

                total++;
                BeirCorpusDocument document = objectMapper.readValue(line, BeirCorpusDocument.class);
                if (importDocument(document)) {
                    imported++;
                } else {
                    skipped++;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Import SciFact corpus jsonl failed: " + corpusJsonlPath, e);
        }

        return new BeirImportResponse(total, imported, skipped);
    }

    @Transactional
    public boolean importDocument(BeirCorpusDocument document) {
        validateDocument(document);

        Long evalFileId = toEvalFileId(document.getDocId());
        if (alreadyImported(evalFileId)) {
            return false;
        }

        String text = buildDocumentText(document);
        List<String> chunks = splitText(text);
        if (chunks.isEmpty()) {
            return false;
        }

        List<List<Float>> embeddings = llmService.embed(chunks);
        saveChunks(evalFileId, chunks, embeddings);
        return true;
    }

    private void validateDocument(BeirCorpusDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document cannot be null");
        }
        if (document.getDocId() == null || document.getDocId().isBlank()) {
            throw new IllegalArgumentException("SciFact doc_id cannot be empty");
        }
    }

    private boolean alreadyImported(Long evalFileId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM text_chunk WHERE file_id = ?",
                Integer.class,
                evalFileId
        );
        return count != null && count > 0;
    }

    private Long toEvalFileId(String beirDocId) {
        try {
            return -(Long.parseLong(beirDocId.trim()) + 1);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("SciFact doc_id must be numeric: " + beirDocId, e);
        }
    }

    private String buildDocumentText(BeirCorpusDocument document) {
        if (document.getContent() != null && !document.getContent().isBlank()) {
            return document.getContent().trim();
        }

        String title = document.getTitle() == null ? "" : document.getTitle().trim();
        String text = document.getText() == null ? "" : document.getText().trim();
        return (title + "\n" + text).trim();
    }

    private List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String[] paragraphs = text.split("\\n+");
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (currentChunk.length() + paragraph.length() <= MAX_CHUNK_SIZE) {
                currentChunk.append(paragraph).append("\n");
            } else {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                }
                currentChunk = new StringBuilder(paragraph).append("\n");
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        List<String> refinedChunks = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.length() > MAX_CHUNK_SIZE) {
                refinedChunks.addAll(splitLargeChunk(chunk));
            } else if (!chunk.isBlank()) {
                refinedChunks.add(chunk);
            }
        }

        return addOverlap(mergeSmallChunks(refinedChunks));
    }

    private List<String> splitLargeChunk(String text) {
        List<String> result = new ArrayList<>();
        recursiveSplit(text, result, 0);
        return result;
    }

    private void recursiveSplit(String text, List<String> result, int level) {
        if (text.length() <= MAX_CHUNK_SIZE) {
            result.add(text.trim());
            return;
        }

        if (level >= SPLIT_SYMBOLS.length) {
            forceSplit(text, result);
            return;
        }

        String symbol = SPLIT_SYMBOLS[level];
        String[] parts = text.split(java.util.regex.Pattern.quote(symbol));
        StringBuilder buffer = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            String segment = parts[i];
            if (i < parts.length - 1) {
                segment += symbol;
            }

            if (buffer.length() + segment.length() <= MAX_CHUNK_SIZE) {
                buffer.append(segment);
            } else {
                if (buffer.length() > 0) {
                    recursiveSplit(buffer.toString(), result, level + 1);
                }
                buffer = new StringBuilder(segment);
            }
        }

        if (buffer.length() > 0) {
            recursiveSplit(buffer.toString(), result, level + 1);
        }
    }

    private void forceSplit(String text, List<String> result) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + MAX_CHUNK_SIZE, text.length());
            result.add(text.substring(start, end).trim());
            start = end;
        }
    }

    private List<String> mergeSmallChunks(List<String> chunks) {
        List<String> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String chunk : chunks) {
            if (buffer.length() + chunk.length() <= MAX_CHUNK_SIZE) {
                buffer.append(chunk).append(" ");
            } else {
                if (buffer.length() > 0) {
                    result.add(buffer.toString().trim());
                }
                buffer = new StringBuilder(chunk);
            }
        }

        if (buffer.length() > 0) {
            result.add(buffer.toString().trim());
        }

        return result;
    }

    private List<String> addOverlap(List<String> chunks) {
        List<String> result = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String current = chunks.get(i);
            if (i > 0) {
                String previous = chunks.get(i - 1);
                int overlapStart = Math.max(0, previous.length() - OVERLAP_SIZE);
                current = previous.substring(overlapStart) + current;
            }
            result.add(current);
        }

        return result;
    }

    private void saveChunks(Long evalFileId, List<String> chunks, List<List<Float>> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunks and embeddings size mismatch");
        }

        List<TextChunk> textChunks = new ArrayList<>();
        List<TextChunkVector> vectors = new ArrayList<>();
        Snowflake snowflake = IdUtil.getSnowflake(2, 1);
        int offset = 0;

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            Long chunkId = snowflake.nextId();
            LocalDateTime now = LocalDateTime.now();

            TextChunk textChunk = new TextChunk();
            textChunk.setChunkId(chunkId);
            textChunk.setFileId(evalFileId);
            textChunk.setChunkIndex(i);
            textChunk.setChunkText(chunk);
            textChunk.setChunkLength(chunk.length());
            textChunk.setStartOffset(offset);
            textChunk.setEndOffset(offset + chunk.length());
            textChunk.setStatus("BEIR_SCIFACT");
            textChunk.setCreatedAt(now);
            textChunk.setUpdatedAt(now);
            textChunks.add(textChunk);

            List<Float> embeddingList = embeddings.get(i);
            float[] embedding = new float[embeddingList.size()];
            for (int j = 0; j < embeddingList.size(); j++) {
                embedding[j] = embeddingList.get(j);
            }

            TextChunkVector vector = new TextChunkVector();
            vector.setChunkId(chunkId);
            vector.setFileId(evalFileId);
            vector.setChunkIndex(i);
            vector.setEmbedding(embedding);
            vector.setCreatedAt(now);
            vectors.add(vector);

            offset += chunk.length();
        }

        textChunkMapper.batchInsert(textChunks);
        textChunkVectorMapper.batchInsert(vectors);
        keywordSearchService.indexChunks(textChunks);
    }
}
