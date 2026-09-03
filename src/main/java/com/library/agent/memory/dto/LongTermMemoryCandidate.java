package com.library.agent.memory.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆候选，抽取通道（LLM 输出解析）与手动添加共用。
 * 不含溯源与访问统计等库内派生字段，由服务层在入库时补齐。
 */
@Data
public class LongTermMemoryCandidate {

    /**
     * 记忆类别：USER_PROFILE / PREFERENCE / CONSTRAINT / ENTITY / EXPERIENCE
     */
    private String category;

    /**
     * 记忆正文，自包含、无指代
     */
    private String content;

    /**
     * 关键词列表
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
     * 重要度 1-10，缺省由服务层按 5 处理
     */
    private Integer importance;

    /**
     * 置信度 0-1，缺省由服务层按 0.8 处理
     */
    private Double confidence;

    /**
     * 去重键（&lt;CATEGORY&gt;:&lt;归一化主题&gt;），冲突消解依据
     */
    private String dedupKey;

    /**
     * 显式过期时间，可选（抽取通道不填；手动添加可指定）
     */
    private LocalDateTime expiredAt;
}
