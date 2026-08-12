package com.library.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话轮次追踪实体，记录一轮对话（用户提问 + LLM 回答）的汇总信息。
 */
@Data
public class ConversationTrace {

    private Long id;
    private String traceId;
    private Long userId;
    private String conversationId;
    private String userQuery;
    private String intentType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer totalDurationMs;
    private Integer totalInputTokens;
    private Integer totalOutputTokens;
    private Integer totalTokens;
    private Integer llmCallCount;
    private Integer toolCallCount;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
}
