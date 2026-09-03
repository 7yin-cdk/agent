package com.library.agent.memory.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆展示 VO，剔除 embedding 大字段。
 * 管理分页/详情/召回调试接口共用；召回调试时填充 similarity。
 */
@Data
public class MemoryView {

    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 记忆类别
     */
    private String category;

    /**
     * 记忆正文
     */
    private String content;

    /**
     * 关键词列表
     */
    private List<String> keywords;

    /**
     * 实体名
     */
    private String entity;

    /**
     * 实体类型
     */
    private String entityType;

    /**
     * 重要度 1-10
     */
    private Integer importance;

    /**
     * 置信度 0-1
     */
    private Double confidence;

    /**
     * 去重键
     */
    private String dedupKey;

    /**
     * 累计被召回次数
     */
    private Integer accessCount;

    /**
     * 最近一次被召回时间
     */
    private LocalDateTime lastAccessedAt;

    /**
     * 来源会话 ID
     */
    private String sourceConversationId;

    /**
     * 来源轮次
     */
    private String sourceTurn;

    /**
     * 扩展元数据 JSONB
     */
    private Map<String, Object> metadata;

    /**
     * 显式过期时间
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
     * 召回相似度，仅 search 调试接口填充
     */
    private Double similarity;
}
