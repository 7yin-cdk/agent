package com.library.agent.rag.service.impl;

import com.library.agent.MQ.Message.RagIngestMessage;
import com.library.agent.MQ.producer.RagIngestProducer;
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
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

/**
 * RAG 知识库服务实现。
 * <p>
 * 该服务负责文档上传、异步入库消息发送、向量检索以及 RAG Prompt 构建。
 */
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
    public void queryStream(
            String text,
            String conversationSummary,
            List<AgentShortTermMemory> historyMessages,
            Consumer<String> onDelta
    ) {
        queryStream(text, text, conversationSummary, historyMessages, onDelta);
    }

    @Override
    public void queryStream(
            String text,
            String rewrittenQuestion,
            String conversationSummary,
            List<AgentShortTermMemory> historyMessages,
            Consumer<String> onDelta
    ) {
        String prompt = buildRagPrompt(text, rewrittenQuestion, conversationSummary, historyMessages);
        llmService.chatStream(prompt, onDelta);
    }

    private String buildRagPrompt(
            String text,
            String rewrittenQuestion,
            String conversationSummary,
            List<AgentShortTermMemory> historyMessages
    ) {
        String retrievalQuestion = normalizeRetrievalQuestion(text, rewrittenQuestion);
        List<Float> embed = llmService.embed(retrievalQuestion);

        // pgvector 查询使用 float[]，这里将模型返回的 List<Float> 转换成数组。
        float[] vector = new float[embed.size()];
        for (int i = 0; i < embed.size(); i++) {
            vector[i] = embed.get(i);
        }
        //向量检索出TopK
        List<Long> vectorIds = textChunkVectorMapper.selectTopKChunkIds(vector, 100);
        //关键词检索出TopK
        List<Long> keywordChunkIds = keywordSearchService.searchChunkIds(retrievalQuestion, 100);
        //取RFF倒排后的TopK
        List<Long> chunkIds = mergeByRrf(vectorIds, keywordChunkIds, 80);
        List<TextChunk> textChunks = textChunkMapper.selectByChunkIds(chunkIds);
        List<String> chunks = new ArrayList<>();
        for (TextChunk textChunk : textChunks) {
            chunks.add(textChunk.getChunkText());
        }
        //重排序，获取到重排序后的chunk的索引
        List<Integer> rerankChunkIds = llmService.rerank(retrievalQuestion, chunks, 5, 0.7);
        List<String> rerankChunks = new ArrayList<>();
        for (Integer rerankChunkId : rerankChunkIds) {
            rerankChunks.add(chunks.get(rerankChunkId));
        }
        return PromptBuilder.buildRagPrompt(text, retrievalQuestion, conversationSummary, historyMessages, rerankChunks);
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
     * RFF倒排算法取混合检索后的TopK
     * @param vectorIds 向量检索的文档id
     * @param keywordIds 关键词检索的文档id
     * @param limit 最终需要的TopK
     * @return
     */
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

    /**
     * 为文档赋RFF分
     * @param scores
     * @param ids
     */
    private void addRrfScores(Map<Long, Double> scores, List<Long> ids) {
        for (int i = 0; i < ids.size(); i++) {
            scores.merge(ids.get(i), 1.0 / (60 + i + 1), Double::sum);
        }
    }
}
