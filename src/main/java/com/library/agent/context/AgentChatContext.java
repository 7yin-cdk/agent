package com.library.agent.context;

import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.enums.IntentType;
import lombok.Data;

import java.util.List;

/**
 * Agent 单次聊天上下文。
 * <p>
 * 该对象用于在意图识别、路由、Prompt 构建和记忆写入之间传递同一批上下文数据，
 * 避免各个路径反复传递零散参数。
 */
@Data
public class AgentChatContext {

    /**
     * 当前登录用户 ID。
     */
    private Long userId;

    /**
     * 当前聊天所属会话 ID。
     */
    private String conversationId;

    /**
     * 用户本轮问题。
     */
    private String query;

    /**
     * 本轮问题识别出的意图类型。
     */
    private IntentType intentType;

    /**
     * 当前会话内召回的短期记忆消息。
     */
    private List<AgentShortTermMemory> historyMessages;
}
