package com.library.agent.memory.impl;

import com.library.agent.entity.AgentConversationSummary;
import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.llm.LlmService;
import com.library.agent.mapper.AgentConversationSummaryMapper;
import com.library.agent.mapper.AgentShortTermMemoryMapper;
import com.library.agent.memory.ConversationSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSummaryServiceImpl implements ConversationSummaryService {

    private static final int RECENT_WINDOW_SIZE = 20;
    private static final int SUMMARY_BATCH_MESSAGES = 10;
    private static final int MAX_SUMMARY_CHARS = 2000;

    private final AgentConversationSummaryMapper conversationSummaryMapper;
    private final AgentShortTermMemoryMapper shortTermMemoryMapper;
    private final LlmService llmService;

    @Override
    public String getSummary(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return "";
        }

        AgentConversationSummary summary = conversationSummaryMapper.selectByUserIdAndConversationId(
                String.valueOf(userId),
                conversationId
        );
        if (summary == null || summary.getSummary() == null) {
            return "";
        }
        return summary.getSummary();
    }

    @Async
    @Override
    public void triggerSummaryIfNeeded(Long userId, String conversationId) {
        try {
            doTriggerSummaryIfNeeded(userId, conversationId);
        } catch (Exception e) {
            log.warn("Failed to update conversation summary, userId={}, conversationId={}", userId, conversationId, e);
        }
    }

    /**
     * 判断是否生成摘要
     * @param userId
     * @param conversationId
     */
    private void doTriggerSummaryIfNeeded(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return;
        }

        String userIdText = String.valueOf(userId);
        // 获取最新一条消息的id
        Long latestOrder = shortTermMemoryMapper.selectMaxMessageOrder(userIdText, conversationId);
        if (latestOrder == null || latestOrder <= RECENT_WINDOW_SIZE) {
            return;
        }

        AgentConversationSummary oldSummary = conversationSummaryMapper.selectByUserIdAndConversationId(
                userIdText,
                conversationId
        );
        long coveredOrder = oldSummary == null || oldSummary.getCoveredMessageOrder() == null
                ? 0L
                : oldSummary.getCoveredMessageOrder();
        long maxSummarizableOrder = latestOrder - RECENT_WINDOW_SIZE;
        if (maxSummarizableOrder <= coveredOrder) {
            return;
        }

        int candidateCount = shortTermMemoryMapper.countMessagesInOrderRange(
                userIdText,
                conversationId,
                coveredOrder + 1,
                maxSummarizableOrder
        );
        if (candidateCount < SUMMARY_BATCH_MESSAGES) {
            return;
        }

        List<AgentShortTermMemory> messages = shortTermMemoryMapper.selectMessagesInOrderRange(
                userIdText,
                conversationId,
                coveredOrder + 1,
                maxSummarizableOrder,
                SUMMARY_BATCH_MESSAGES
        );
        if (messages == null || messages.size() < SUMMARY_BATCH_MESSAGES) {
            return;
        }

        Long newCoveredOrder = messages.get(messages.size() - 1).getMessageOrder();
        if (newCoveredOrder == null || newCoveredOrder <= coveredOrder) {
            return;
        }

        String newSummaryText = llmService.chat(buildSummaryPrompt(
                oldSummary == null ? "" : oldSummary.getSummary(),
                messages
        ));
        if (newSummaryText == null || newSummaryText.isBlank()) {
            return;
        }

        AgentConversationSummary newSummary = new AgentConversationSummary();
        newSummary.setUserId(userIdText);
        newSummary.setConversationId(conversationId);
        newSummary.setSummary(trimSummary(newSummaryText));
        newSummary.setCoveredMessageOrder(newCoveredOrder);
        newSummary.setCreatedAt(LocalDateTime.now());
        newSummary.setUpdatedAt(LocalDateTime.now());
        conversationSummaryMapper.upsert(newSummary);
    }

    private String buildSummaryPrompt(String oldSummary, List<AgentShortTermMemory> messages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a conversation memory summarizer.\n");
        prompt.append("Update the existing summary using only the new conversation messages.\n");
        prompt.append("Keep stable user goals, preferences, constraints, decisions, important entities, file names, interface names, parameters, errors, and unresolved issues.\n");
        prompt.append("Remove greetings, repeated content, and low-value chatter. Do not invent facts.\n");
        prompt.append("Write the updated summary in Chinese. Keep it concise and structured, within 1000 Chinese characters.\n\n");

        prompt.append("### Existing Summary\n");
        if (oldSummary == null || oldSummary.isBlank()) {
            prompt.append("None.\n\n");
        } else {
            prompt.append(oldSummary.trim()).append("\n\n");
        }

        prompt.append("### New Messages\n");
        for (AgentShortTermMemory message : messages) {
            if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            prompt.append(normalizeRole(message.getRole()))
                    .append(": ")
                    .append(message.getContent().trim())
                    .append("\n");
        }
        prompt.append("\n### Updated Summary\n");
        return prompt.toString();
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "unknown";
        }
        return role.trim().toLowerCase();
    }

    private String trimSummary(String summary) {
        String trimmed = summary.trim();
        if (trimmed.length() <= MAX_SUMMARY_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_SUMMARY_CHARS);
    }
}
