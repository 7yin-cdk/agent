package com.library.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 会话实体类
 * 对应 PostgreSQL 表 agent_conversation
 */
@Data
public class AgentConversation {

    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 用户 ID，用于隔离不同用户的会话
     */
    private Long userId;

    /**
     * 对外暴露的会话 ID
     */
    private String conversationId;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 会话状态：ACTIVE、DELETED、ARCHIVED
     */
    private String status;

    /**
     * 当前会话消息数量
     */
    private Integer messageCount;

    /**
     * 最近一条消息时间
     */
    private LocalDateTime lastMessageAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
