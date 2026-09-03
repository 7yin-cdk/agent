package com.library.agent.memory.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 修改长期记忆请求，字段全可选，null 表示不修改该字段。
 */
@Data
public class MemoryUpdateRequest {

    /**
     * 新的记忆类别
     */
    private String category;

    /**
     * 新的记忆正文；修改正文时服务层会重算 embedding
     */
    private String content;

    /**
     * 新的关键词列表
     */
    private List<String> keywords;

    /**
     * 新的实体名
     */
    private String entity;

    /**
     * 新的重要度 1-10
     */
    private Integer importance;

    /**
     * 新的置信度 0-1
     */
    private Double confidence;

    /**
     * 新的过期时间；null 表示不修改（与其它可选字段一致）。
     * 已设过期的记忆如需改为永久保留，请删除后重建。
     */
    private LocalDateTime expiredAt;
}
