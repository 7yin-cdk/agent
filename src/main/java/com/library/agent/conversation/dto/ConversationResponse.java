package com.library.agent.conversation.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话响应
 */
@Data
public class ConversationResponse {

    /**
     * 会话 ID
     */
    private String conversationId;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 会话状态
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
