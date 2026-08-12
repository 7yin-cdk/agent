package com.library.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单次工具调用记录，属于某个 ConversationTrace。
 */
@Data
public class ToolCallRecord {

    private Long id;
    private String traceId;
    private Integer callSequence;
    private String toolName;
    private String toolInput;
    private String toolOutput;
    private Boolean success;
    private Integer durationMs;
    private LocalDateTime createdAt;
}
