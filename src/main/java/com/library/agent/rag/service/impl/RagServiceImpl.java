package com.library.agent.rag.service.impl;

import com.library.agent.MQ.Message.RagIngestMessage;
import com.library.agent.MQ.producer.RagIngestProducer;
import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.entity.FileMetadata;
import com.library.agent.entity.TextChunk;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        List<Float> embed = llmService.embed(text);

        // pgvector 查询使用 float[]，这里将模型返回的 List<Float> 转换成数组。
        float[] vector = new float[embed.size()];
        for (int i = 0; i < embed.size(); i++) {
            vector[i] = embed.get(i);
        }

        List<Long> chunkIds = textChunkVectorMapper.selectTopKChunkIds(vector, 5);
        List<TextChunk> textChunks = textChunkMapper.selectByChunkIds(chunkIds);
        List<String> chunks = new ArrayList<>();
        for (TextChunk textChunk : textChunks) {
            chunks.add(textChunk.getChunkText());
        }

        String prompt = PromptBuilder.buildRagPrompt(text, conversationSummary, historyMessages, chunks);
        return llmService.chat(prompt);
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
}
