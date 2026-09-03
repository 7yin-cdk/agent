package com.library.agent.rag.service.impl;

import com.library.agent.MQ.Message.RagIngestMessage;
import com.library.agent.MQ.producer.RagIngestProducer;
import com.library.agent.entity.AgentLongTermMemory;
import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.entity.FileMetadata;
import com.library.agent.entity.TextChunk;
import com.library.agent.es.service.KeywordSearchService;
import com.library.agent.llm.LlmService;
import com.library.agent.llm.PromptBuilder;
import com.library.agent.mapper.FileMetadataMapper;
import com.library.agent.mapper.TextChunkMapper;
import com.library.agent.mapper.TextChunkVectorMapper;
import com.library.agent.rag.service.RagService;
import com.library.agent.rag.service.RrfMerger;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

/**
 * RAG 知识库服务实现。
 * <p>
 * 该服务负责文档上传、异步入库消息发送、向量检索以及 RAG Prompt 构建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagServiceImpl implements RagService {

    private final MinioClient minioClient;
    private final FileMetadataMapper fileMetadataMapper;
    private final RagIngestProducer ragIngestProducer;
    private final LlmService llmService;
    private final TextChunkVectorMapper textChunkVectorMapper;
    private final TextChunkMapper textChunkMapper;
    private final KeywordSearchService keywordSearchService;

    /**
     * MinIO 存储桶名称。
     */
    @Value("${minio.bucket}")
    private String bucketName;

    /**
     * 上传文档并发送异步 RAG 入库消息。
     */
    @Override
    public void ingest(MultipartFile file) {
        uploadAndEnqueue(file);
    }

    /**
     * 上传文档：MinIO 存原文 → 写 file_metadata(UPLOADED) → 发异步入库消息。
     */
    @Override
    public FileMetadata uploadAndEnqueue(MultipartFile file) {
        try {
            String objectName = uploadToMinio(file);
            String fileUrl = bucketName + "/" + objectName;

            FileMetadata metadata = new FileMetadata();
            metadata.setFileName(file.getOriginalFilename());
            metadata.setFileUrl(fileUrl);
            metadata.setFileSize(file.getSize());
            metadata.setContentType(file.getContentType());
            metadata.setBucketName(bucketName);
            metadata.setObjectName(objectName);
            metadata.setStatus("UPLOADED");
            metadata.setCreatedAt(LocalDateTime.now());
            metadata.setUpdatedAt(LocalDateTime.now());

            fileMetadataMapper.insert(metadata);

            RagIngestMessage message = new RagIngestMessage(
                    metadata.getId(),
                    bucketName,
                    objectName,
                    file.getOriginalFilename()
            );
            ragIngestProducer.send(message);
            return metadata;
        } catch (Exception e) {
            throw new RuntimeException("RAG ingest 失败", e);
        }
    }

    /**
     * 基于用户问题检索知识库资料，并结合当前会话历史构建 RAG Prompt。
     */
    @Override
    public String query(String text, List<AgentShortTermMemory> historyMessages) {
        return query(text, null, historyMessages);
    }

    @Override
    public String query(String text, String conversationSummary, List<AgentShortTermMemory> historyMessages) {
        return query(text, text, conversationSummary, historyMessages);
    }

    @Override
    public String query(
            String text,
            String rewrittenQuestion,
            String conversationSummary,
            List<AgentShortTermMemory> historyMessages
    ) {
        StringBuilder answer = new StringBuilder();
        queryStream(text, rewrittenQuestion, conversationSummary, historyMessages, answer::append);
        return answer.toString();
    }

    @Override
    public String queryStream(
            String text,
            String conversationSummary,
            List<AgentShortTermMemory> historyMessages,
            Consumer<String> onDelta
    ) {
        return queryStream(text, text, conversationSummary, historyMessages, onDelta);
    }

    @Override
    public String queryStream(
            String text,
            String rewrittenQuestion,
            String conversationSummary,
            List<AgentShortTermMemory> historyMessages,
            Consumer<String> onDelta
    ) {
        return queryStream(text, rewrittenQuestion, conversationSummary, historyMessages, List.of(), onDelta);
    }

    @Override
    public String queryStream(
            String text,
            String rewrittenQuestion,
            String conversationSummary,
            List<AgentShortTermMemory> historyMessages,
            List<AgentLongTermMemory> longTermMemories,
            Consumer<String> onDelta
    ) {
        String prompt = buildRagPrompt(text, rewrittenQuestion, conversationSummary, historyMessages, longTermMemories);
        // TODO LLM调用超时时采用下一个LLM
        llmService.chatStream(prompt, onDelta);
        return prompt;
    }

    private String buildRagPrompt(
            String text,
            String rewrittenQuestion,
            String conversationSummary,
            List<AgentShortTermMemory> historyMessages,
            List<AgentLongTermMemory> longTermMemories
    ) {
        String retrievalQuestion = normalizeRetrievalQuestion(text, rewrittenQuestion);

        /* 查询向量化 */
        List<Float> embed = llmService.embed(retrievalQuestion);

        float[] vector = new float[embed.size()];
        for (int i = 0; i < embed.size(); i++) {
            vector[i] = embed.get(i);
        }

        /* pgvector 向量检索 */
        List<Long> vectorIds = textChunkVectorMapper.selectTopKChunkIds(vector, 100);

        /* ES 关键词检索 */
        List<Long> keywordChunkIds = keywordSearchService.searchChunkIds(retrievalQuestion, 100);

        /* RRF 融合 */
        List<Long> chunkIds = mergeByRrf(vectorIds, keywordChunkIds, 80);

        List<TextChunk> textChunks = textChunkMapper.selectByChunkIds(chunkIds);
        List<String> chunks = new ArrayList<>();
        for (TextChunk textChunk : textChunks) {
            chunks.add(textChunk.getChunkText());
        }

        /* 重排序 */
        List<Integer> rerankChunkIds = llmService.rerank(retrievalQuestion, chunks, 5, 0.7);

        List<String> rerankChunks = new ArrayList<>();
        for (Integer rerankChunkId : rerankChunkIds) {
            rerankChunks.add(chunks.get(rerankChunkId));
        }

        /* 构建 RAG Prompt（注入长期记忆段） */
        return PromptBuilder.buildRagPrompt(text, retrievalQuestion, conversationSummary, historyMessages,
                rerankChunks, longTermMemories);
    }

    private String normalizeRetrievalQuestion(String text, String rewrittenQuestion) {
        if (rewrittenQuestion == null || rewrittenQuestion.isBlank()) {
            return text;
        }
        return rewrittenQuestion.trim();
    }

    /**
     * 上传文件到 MinIO，并返回对象名。
     */
    private String uploadToMinio(MultipartFile file) throws Exception {
        String originalFilename = file.getOriginalFilename();
        String objectName = UUID.randomUUID() + "_" + originalFilename;

        boolean found = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build()
        );

        if (!found) {
            minioClient.makeBucket(
                    MakeBucketArgs.builder().bucket(bucketName).build()
            );
        }

        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .stream(file.getInputStream(), file.getSize(), -1)
                        .contentType(file.getContentType())
                        .build()
        );

        return objectName;
    }

    /**
     * RRF 倒排融合，委托共享工具 {@link RrfMerger}。
     */
    private List<Long> mergeByRrf(List<Long> vectorIds, List<Long> keywordIds, int limit) {
        return RrfMerger.merge(vectorIds, keywordIds, limit);
    }

    /**
     * 级联删除文档：Postgres 三表（事务内）+ ES/MinIO 尽力清理。
     */
    @Override
    @Transactional
    public void deleteDocument(Long fileId) {
        FileMetadata metadata = fileMetadataMapper.selectById(fileId);
        if (metadata == null) {
            throw new IllegalArgumentException("文档不存在: " + fileId);
        }
        textChunkMapper.deleteByFileId(fileId);
        textChunkVectorMapper.deleteByFileId(fileId);
        fileMetadataMapper.deleteById(fileId);

        try {
            keywordSearchService.deleteByFileId(fileId);
        } catch (Exception e) {
            log.warn("删除 ES 分片失败 fileId={}", fileId, e);
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(metadata.getBucketName())
                    .object(metadata.getObjectName())
                    .build());
        } catch (Exception e) {
            log.warn("删除 MinIO 对象失败 fileId={}", fileId, e);
        }
    }
}
