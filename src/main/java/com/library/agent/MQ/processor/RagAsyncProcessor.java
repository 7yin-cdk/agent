package com.library.agent.MQ.processor;

import com.library.agent.MQ.Message.RagIngestMessage;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.library.agent.entity.TextChunk;
import com.library.agent.entity.TextChunkVector;
import com.library.agent.es.service.KeywordSearchService;
import com.library.agent.llm.LlmService;
import com.library.agent.mapper.FileMetadataMapper;
import com.library.agent.mapper.TextChunkMapper;
import com.library.agent.mapper.TextChunkVectorMapper;
import com.library.agent.rag.dto.ChunkDraft;
import com.library.agent.rag.dto.ParsedDocument;
import com.library.agent.rag.service.DocumentParser;
import com.library.agent.rag.service.MarkdownChunker;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG流程异步任务执行器
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagAsyncProcessor {

    private static final String[] SPLIT_SYMBOLS = {
            "\n",
            "。", "！", "？",
            "，", ","
    };

    private static final int MAX_CHUNK_SIZE = 400;
    private static final int OVERLAP_SIZE = 100;
    private final TextChunkVectorMapper textChunkVectorMapper;
    private final KeywordSearchService keywordSearchService;
    private final TextChunkMapper textChunkMapper;
    private final FileMetadataMapper fileMetadataMapper;
    private final MinioClient minioClient;
    private final DocumentParser documentParser;
    private final MarkdownChunker markdownChunker;
    private final LlmService llmService;

    public void process(RagIngestMessage message) {
        String bucketName = message.getBucketName();
        String objectName = message.getObjectName();
        Long fileId = message.getFileId();

        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build()
        )) {
            /* 1. 解析文档：markdown 保留空行与缩进，其余格式沿用 Tika 压平清洗 */
            ParsedDocument document = documentParser.parse(inputStream, message.getFileName());

            /* 2. 分块：markdown 按标题层级切分，其余沿用标点递归切分 */
            List<ChunkDraft> chunks = toChunks(document);

            /* 3. 分片文本向量化 */
            List<List<Float>> embed = llmService.embed(chunks.stream().map(ChunkDraft::getText).toList());

            /* 4. 分片和分片向量化结果入库，成功后回写文件状态 */
            saveChunks(fileId, chunks, embed, document);
            fileMetadataMapper.updateStatus(fileId, "EMBEDDED");
        } catch (Exception e) {
            /* 记录失败并回写 FAILED，供前端展示（不再抛出以避免无限重试） */
            log.error("RAG 入库失败 fileId={}", fileId, e);
            fileMetadataMapper.updateStatus(fileId, "FAILED");
        }
    }

    /**
     * 按文档类型选择分块策略。
     */
    private List<ChunkDraft> toChunks(ParsedDocument document) {
        if (document.isMarkdown()) {
            return markdownChunker.chunk(document.getText(), document.getSourceTitle());
        }
        /* 非 markdown 没有小节概念，偏移量按块长累加，保持原有语义 */
        List<ChunkDraft> drafts = new ArrayList<>();
        int offset = 0;
        for (String chunk : splitText(document.getText())) {
            drafts.add(new ChunkDraft(chunk, null, offset, offset + chunk.length()));
            offset += chunk.length();
        }
        return drafts;
    }

    /**
     * 文本分块主流程
     */
    private List<String> splitText(String text) {

        // 1. 段落切分（修复空行问题）
        String[] paragraphs = text.split("\\n+");

        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        // 合并较短的段落
        for (String para : paragraphs) {
            if (currentChunk.length() + para.length() <= MAX_CHUNK_SIZE) {
                currentChunk.append(para).append("\n");
            } else {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                }
                currentChunk = new StringBuilder(para + "\n");
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        // 2. 递归拆分大块
        List<String> refinedChunks = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.length() > MAX_CHUNK_SIZE) {
                refinedChunks.addAll(splitLargeChunk(chunk));
            } else {
                refinedChunks.add(chunk);
            }
        }

        // 3. 合并小块（修复无限膨胀问题）
        List<String> mergedChunks = mergeSmallChunks(refinedChunks);

        // 4. 添加 overlap（修复指数增长问题）
        return addOverlap(mergedChunks);
    }

    /**
     * 拆分较大分块
     * @param text
     * @return
     */
    private List<String> splitLargeChunk(String text) {
        List<String> result = new ArrayList<>();
        recursiveSplit(text, result, 0);
        return result;
    }

    /**
     * 递归切分
     * @param text
     * @param result
     * @param level
     */
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
        String[] parts = text.split(symbol);

        StringBuilder buffer = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {

            String part = parts[i];
            String segment = part;

            // 修复：最后一个不加分隔符
            if (i < parts.length - 1) {
                segment += symbol;
            }

            if (buffer.length() + segment.length() <= MAX_CHUNK_SIZE) {
                buffer.append(segment);
            } else {
                recursiveSplit(buffer.toString(), result, level + 1);
                buffer = new StringBuilder(segment);
            }
        }

        if (buffer.length() > 0) {
            recursiveSplit(buffer.toString(), result, level + 1);
        }
    }

    /**
     * 强制截断分块
     * @param text
     * @param result
     */
    private void forceSplit(String text, List<String> result) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + MAX_CHUNK_SIZE, text.length());
            result.add(text.substring(start, end));
            start = end;
        }
    }

    /**
     * 合并较小分块
     * @param chunks
     * @return
     */
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

    /**
     * 相邻分块之间添加重叠区
     * @param chunks
     * @return
     */
    private List<String> addOverlap(List<String> chunks) {
        List<String> result = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String current = chunks.get(i);

            if (i > 0) {
                String prev = chunks.get(i - 1);
                int overlapStart = Math.max(0, prev.length() - OVERLAP_SIZE);
                String overlap = prev.substring(overlapStart);

                current = overlap + current;
            }
            result.add(current);
        }

        return result;
    }

    /**
     * 将分块文本和分块文本向量化结果入库
     * @param fileId 分块所属文件 ID
     * @param chunks 分块内容，markdown 分块自带小节路径
     * @param embeddings 分块向量化结果
     * @param document 文档解析结果，提供来源标题与来源地址
     * @return 分块 ID 列表
     */
    @Transactional
    public List<Long> saveChunks(Long fileId, List<ChunkDraft> chunks, List<List<Float>> embeddings,
                                 ParsedDocument document) {

        List<TextChunk> entities = new ArrayList<>();
        List<TextChunkVector> vectorEntities = new ArrayList<>();
        List<Long> chunkIds = new ArrayList<>();

        Snowflake snowflake = IdUtil.getSnowflake(1, 1);

        for (int i = 0; i < chunks.size(); i++) {
            ChunkDraft draft = chunks.get(i);
            List<Float> embeddingList = embeddings.get(i);

            Long id = snowflake.nextId();
            chunkIds.add(id);

            // 文本 chunk
            TextChunk entity = new TextChunk();
            entity.setChunkId(id);
            entity.setFileId(fileId);
            entity.setChunkIndex(i);
            entity.setChunkText(draft.getText());
            entity.setChunkLength(draft.getText().length());
            entity.setStartOffset(draft.getStartOffset());
            entity.setEndOffset(draft.getEndOffset());
            entity.setSourceTitle(document.getSourceTitle());
            entity.setSourceUrl(document.getSourceUrl());
            entity.setSectionPath(draft.getSectionPath());
            entity.setStatus("INIT");
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            entities.add(entity);

            // 向量 chunk
            TextChunkVector vectorEntity = new TextChunkVector();
            vectorEntity.setChunkId(id);
            vectorEntity.setFileId(fileId);
            vectorEntity.setChunkIndex(i);

            // 将 List<Float> 转 float[]
            float[] vector = new float[embeddingList.size()];
            for (int j = 0; j < embeddingList.size(); j++) {
                vector[j] = embeddingList.get(j);
            }
            vectorEntity.setEmbedding(vector);

            vectorEntity.setCreatedAt(LocalDateTime.now());
            vectorEntities.add(vectorEntity);
        }

        // 批量入库
        textChunkMapper.batchInsert(entities);
        textChunkVectorMapper.batchInsert(vectorEntities);
        keywordSearchService.indexChunks(entities);

        return chunkIds;
    }
}
