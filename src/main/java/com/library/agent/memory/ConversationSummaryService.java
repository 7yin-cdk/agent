package com.library.agent.memory;

public interface ConversationSummaryService {

    String getSummary(Long userId, String conversationId);

    void triggerSummaryIfNeeded(Long userId, String conversationId);
}
