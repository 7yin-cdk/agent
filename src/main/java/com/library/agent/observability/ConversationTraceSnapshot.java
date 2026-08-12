package com.library.agent.observability;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * ConversationTrace 汇总快照，用于持久化前传递数据。
 */
@Data
public class ConversationTraceSnapshot {

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
