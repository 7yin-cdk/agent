package com.library.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 长期记忆实体类。
 * 对应 PostgreSQL 表 agent_long_term_memory，跨会话按用户维度持久化的第三层记忆。
 * 记忆正文存 content，向量存 embedding（1536 维），辅助列支撑等值过滤、分页、召回与淘汰。
 */
@Data
public class AgentLongTermMemory {

    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 用户 ID，用于隔离不同用户的长期记忆
     */
    private String userId;

    /**
     * 记忆类别：USER_PROFILE / PREFERENCE / CONSTRAINT / ENTITY / EXPERIENCE，见 MemoryCategory
     */
    private String category;

    /**
     * 记忆正文，自包含、无指代，召回后直接进入 prompt
     */
    private String content;

    /**
     * 关键词列表，对应 PostgreSQL TEXT[]，参与关键词重叠召回
     */
    private List<String> keywords;

    /**
     * 实体名（集群/库/表/任务/实例等），ENTITY 类记忆应填写
     */
    private String entity;

    /**
     * 实体类型
     */
    private String entityType;

    /**
     * 文本向量表示，1536 维（text-embedding-v4），embedding 失败时可为空但不影响等值召回
     */
    private float[] embedding;

    /**
     * 重要度 1-10，默认 5；越高越优先保留
     */
    private Integer importance;

    /**
     * 置信度 0-1，默认 0.8
     */
    private Double confidence;

    /**
     * 去重键（如 USER_PROFILE:负责系统），配合部分唯一索引 uk_ltm_dedup 做冲突消解
     */
    private String dedupKey;

    /**
     * 累计被召回次数，淘汰打分用
     */
    private Integer accessCount;

    /**
     * 最近一次被召回时间，淘汰打分用
     */
    private LocalDateTime lastAccessedAt;

    /**
     * 溯源：来源会话 ID
     */
    private String sourceConversationId;

    /**
     * 溯源：来源轮次标记
     */
    private String sourceTurn;

    /**
     * 扩展元数据 JSONB，如 EXPERIENCE 的 result（success/failure）
     */
    private Map<String, Object> metadata;

    /**
     * 逻辑删除标记
     */
    private Boolean deleted;

    /**
     * 显式过期时间，超过后不再作为召回上下文；也可由定时任务物理清理
     */
    private LocalDateTime expiredAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 向量召回相似度，仅 selectTopKByEmbedding 填充，不落库
     */
    private Double similarity;
}
