package com.library.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单次 LLM 调用记录，属于某个 ConversationTrace。
 */
@Data
public class LlmCallRecord {

    private Long id;
    private String traceId;
    private Integer callSequence;
    private String modelName;
    private String callType;
    private String inputPrompt;
    private String outputResponse;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer durationMs;
    private LocalDateTime createdAt;
}
