package com.library.agent.rag.service.impl;

import com.library.agent.entity.FileMetadata;
import com.library.agent.entity.TextChunk;
import com.library.agent.mapper.FileMetadataMapper;
import com.library.agent.mapper.TextChunkMapper;
import com.library.agent.rag.service.DocumentParser;
import com.library.agent.rag.service.RagService;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RagServiceImpl implements RagService {

    private static final String[] SPLIT_SYMBOLS = {
            "\n",
            "。", "！", "？",
            "，", ","
    };

    private static final int MAX_CHUNK_SIZE = 400;
    private static final int MIN_CHUNK_SIZE = 200;
    private static final int OVERLAP_SIZE = 50;

    private final MinioClient minioClient;

    @Autowired
    private DocumentParser documentParser;
    @Autowired
    private final TextChunkMapper textChunkMapper;
    private final FileMetadataMapper fileMetadataMapper;

    @Value("${minio.bucket}")
    private String bucketName;

    @Override
    public void ingest(MultipartFile file) {
        try {
            // 1. 上传文件到 MinIO
            String objectName = uploadToMinio(file);

            // 2. 构建文件访问URL
            String fileUrl = bucketName + "/" + objectName;

            // 3. 元数据入库
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

            // 4. 解析文档
            String text = documentParser.parse(file);

            // 5. 分块
            List<String> chunks = splitText(text);

            // 6. 分片入库
            saveChunks(metadata.getId(), chunks);

        } catch (Exception e) {
            throw new RuntimeException("RAG ingest 失败", e);
        }
    }

    @Override
    public String query(String question) {
        return "";
    }

    /**
     * 上传文件到 MinIO
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
     * 文本分块主流程
     */
    private List<String> splitText(String text) {

        // 1. 段落切分（修复空行问题）
        String[] paragraphs = text.split("\\n+");

        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

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
     * 拆分大块（递归）
     */
    private List<String> splitLargeChunk(String text) {
        List<String> result = new ArrayList<>();
        recursiveSplit(text, result, 0);
        return result;
    }

    /**
     * 递归切分核心
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
     * 强制截断（兜底）
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
     * 合并小块（控制最大长度）
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
     * 添加重叠区域（防止语义断裂）
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

            // 控制最大长度
            if (current.length() > MAX_CHUNK_SIZE) {
                current = current.substring(0, MAX_CHUNK_SIZE);
            }

            result.add(current);
        }

        return result;
    }

    private void saveChunks(Long fileId, List<String> chunks) {

        List<TextChunk> entities = new ArrayList<>();

        int offset = 0;

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);

            TextChunk entity = new TextChunk();
            entity.setFileId(fileId);
            entity.setChunkIndex(i);
            entity.setChunkText(chunk);
            entity.setChunkLength(chunk.length());

            // 可选：记录偏移（方便调试/高阶玩法）
            entity.setStartOffset(offset);
            entity.setEndOffset(offset + chunk.length());

            entity.setStatus("INIT");

            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());

            offset += chunk.length();

            entities.add(entity);
        }

        // 批量插入（性能关键）
        textChunkMapper.batchInsert(entities);
    }

}