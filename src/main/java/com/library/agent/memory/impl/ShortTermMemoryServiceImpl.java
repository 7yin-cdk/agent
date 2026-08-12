package com.library.agent.memory.impl;

import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.mapper.AgentShortTermMemoryMapper;
import com.library.agent.memory.ShortTermMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 短期记忆服务实现。
 * <p>
 * 当前实现使用 PostgreSQL 表 agent_short_term_memory 存储会话消息，
 * 并通过 userId + conversationId 做用户和会话双重隔离。
 */
@Service
@RequiredArgsConstructor
public class ShortTermMemoryServiceImpl implements ShortTermMemoryService {

    /**
     * 默认消息 token 估算除数。
     * <p>
     * 这里不做精确 tokenizer 计算，只用于后续控制上下文窗口时的粗略参考。
     */
    private static final int TOKEN_ESTIMATE_DIVISOR = 4;

    /**
     * 用户消息角色。
     */
    private static final String ROLE_USER = "user";

    /**
     * 助手消息角色。
     */
    private static final String ROLE_ASSISTANT = "assistant";

    private final AgentShortTermMemoryMapper shortTermMemoryMapper;

    /**
     * 查询当前用户当前会话的最近消息。
     */
    @Override
    public List<AgentShortTermMemory> listRecentMessages(Long userId, String conversationId, int limit) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        int safeLimit = Math.max(limit, 0);
        if (safeLimit == 0) {
            return List.of();
        }
        return shortTermMemoryMapper.selectRecentMessages(String.valueOf(userId), conversationId, safeLimit);
    }

    /**
     * 查询指定会话的全部消息（上限 200 条），用于前端历史展示。
     */
    @Override
    public List<AgentShortTermMemory> listMessagesByConversation(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return shortTermMemoryMapper.selectRecentMessages(String.valueOf(userId), conversationId, 200);
    }

    /**
     * 保存单条会话消息。
     */
    @Override
    @Transactional
    public void saveMessage(Long userId, String conversationId, String role, String content) {
        saveMessage(userId, conversationId, role, content, Map.of());
    }

    @Override
    @Transactional
    public void saveMessage(
            Long userId,
            String conversationId,
            String role,
            String content,
            Map<String, Object> metadata
    ) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        if (role == null || role.isBlank() || content == null || content.isBlank()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        AgentShortTermMemory memory = new AgentShortTermMemory();
        memory.setUserId(String.valueOf(userId));
        memory.setConversationId(conversationId);
        memory.setRole(role);
        memory.setContent(content);
        memory.setTokenCount(estimateTokenCount(content));
        memory.setMessageOrder(nextMessageOrder(userId, conversationId));
        memory.setMetadata(metadata == null ? new HashMap<>() : new HashMap<>(metadata));
        memory.setDeleted(false);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);

        shortTermMemoryMapper.insert(memory);
    }

    /**
     * 保存一次用户问答产生的两条消息。
     * <p>
     * 这里保持 user 消息先写入、assistant 消息后写入，依赖 message_order 保证读取顺序稳定。
     */
    @Override
    @Transactional
    public void saveUserAndAssistantMessages(
            Long userId,
            String conversationId,
            String userMessage,
            String assistantMessage
    ) {
        saveUserAndAssistantMessages(userId, conversationId, userMessage, assistantMessage, Map.of(), Map.of());
    }

    @Override
    @Transactional
    public void saveUserAndAssistantMessages(
            Long userId,
            String conversationId,
            String userMessage,
            String assistantMessage,
            Map<String, Object> userMetadata,
            Map<String, Object> assistantMetadata
    ) {
        saveMessage(userId, conversationId, ROLE_USER, userMessage, userMetadata);
        saveMessage(userId, conversationId, ROLE_ASSISTANT, assistantMessage, assistantMetadata);
    }

    /**
     * 获取当前会话下一条消息顺序号。
     */
    private Long nextMessageOrder(Long userId, String conversationId) {
        Long nextOrder = shortTermMemoryMapper.selectNextMessageOrder(String.valueOf(userId), conversationId);
        return nextOrder == null ? 1L : nextOrder;
    }

    /**
     * 粗略估算消息 token 数量。
     */
    private Integer estimateTokenCount(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil((double) content.length() / TOKEN_ESTIMATE_DIVISOR));
    }
}
