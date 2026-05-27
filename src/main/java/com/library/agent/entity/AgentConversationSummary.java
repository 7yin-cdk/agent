package com.library.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Entity for agent_conversation_summary.
 */
@Data
public class AgentConversationSummary {

    private Long id;

    private String userId;

    private String conversationId;

    private String summary;

    private Long coveredMessageOrder;

    private Integer summaryVersion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
