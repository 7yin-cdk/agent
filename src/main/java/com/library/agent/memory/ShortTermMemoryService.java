package com.library.agent.memory;

import com.library.agent.entity.AgentShortTermMemory;

import java.util.List;

/**
 * Agent 短期记忆服务。
 * <p>
 * 短期记忆只表示某个用户在某个会话内的消息历史，不负责长期记忆、RAG 文档或意图识别。
 */
public interface ShortTermMemoryService {

    /**
     * 查询当前会话最近的短期记忆消息。
     *
     * @param userId 当前登录用户 ID
     * @param conversationId 会话 ID
     * @param limit 最大返回条数
     * @return 按消息顺序正序排列的历史消息
     */
    List<AgentShortTermMemory> listRecentMessages(Long userId, String conversationId, int limit);

    /**
     * 保存一条短期记忆消息。
     *
     * @param userId 当前登录用户 ID
     * @param conversationId 会话 ID
     * @param role 消息角色，例如 user、assistant、tool
     * @param content 消息内容
     */
    void saveMessage(Long userId, String conversationId, String role, String content);

    /**
     * 保存一次完整问答产生的用户消息和助手消息。
     *
     * @param userId 当前登录用户 ID
     * @param conversationId 会话 ID
     * @param userMessage 用户消息
     * @param assistantMessage 助手回复
     */
    void saveUserAndAssistantMessages(Long userId, String conversationId, String userMessage, String assistantMessage);
}
