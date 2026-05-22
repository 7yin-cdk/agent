package com.library.agent.service;

import com.library.agent.context.AgentChatContext;
import com.library.agent.dto.ChatRequest;
import com.library.agent.dto.ChatResponse;
import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.enums.IntentType;

import java.util.List;

/**
 * Agent 聊天编排服务。
 * <p>
 * 该服务负责会话校验、短期记忆读取、意图识别、路径路由和结果写回。
 */
public interface AgentService {

    /**
     * 执行带会话的 Agent 聊天。
     *
     * @param request 聊天请求
     * @return 聊天响应
     */
    ChatResponse chat(ChatRequest request);

    /**
     * 兼容旧调用：直接使用 query 和 conversationId 发起聊天。
     *
     * @param query 用户问题
     * @param conversationId 会话 ID
     * @return 大模型回答内容
     */
    String chat(String query, String conversationId);

    /**
     * 根据当前问题和最近会话历史识别用户意图。
     *
     * @param query 用户问题
     * @param historyMessages 当前会话历史消息
     * @return 意图类型
     */
    IntentType identifyIntent(String query, List<AgentShortTermMemory> historyMessages);

    /**
     * 根据意图将聊天请求路由到对应处理路径。
     *
     * @param intentType 意图类型
     * @param context 单次聊天上下文
     * @return 大模型回答内容
     */
    String route(IntentType intentType, AgentChatContext context);
}
