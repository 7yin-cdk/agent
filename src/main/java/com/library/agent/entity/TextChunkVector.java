package com.library.agent.entity;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 文档 chunk 的向量表示实体类
 * 对应 PostgreSQL 表 text_chunk_vector
 */
@Data
public class TextChunkVector {

    /**
     * chunk_id
     */
    private Long chunkId;

    /**
     * chunk 的向量表示，对应 PostgreSQL VECTOR 类型，维度需和 embedding 模型一致
     */
    private float[] embedding;

    /**
     * 所属文件 ID，可选冗余字段，对应数据库 file_id
     */
    private Long fileId;

    /**
     * chunk 在文件中的顺序索引，可用于排序，对应数据库 chunk_index
     */
    private Integer chunkIndex;

    /**
     * 可选 JSON 元数据，对应数据库 metadata JSONB，例如标签、来源、用户信息等
     */
    private Map<String, Object> metadata;

    /**
     * 向量创建时间，对应数据库 created_at
     */
    private LocalDateTime createdAt;
}