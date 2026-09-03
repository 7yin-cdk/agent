package com.library.agent.memory.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 手动添加长期记忆请求。
 */
@Data
public class MemoryAddRequest {

    /**
     * 记忆类别：USER_PROFILE / PREFERENCE / CONSTRAINT / ENTITY / EXPERIENCE
     */
    private String category;

    /**
     * 记忆正文
     */
    private String content;

    /**
     * 关键词列表，可选
     */
    private List<String> keywords = new ArrayList<>();

    /**
     * 实体名，可选
     */
    private String entity;

    /**
     * 实体类型，可选
     */
    private String entityType;

    /**
     * 重要度 1-10，可选，缺省 5
     */
    private Integer importance;

    /**
     * 置信度 0-1，可选，缺省 0.8
     */
    private Double confidence;

    /**
     * 显式过期时间，可选
     */
    private LocalDateTime expiredAt;
}
