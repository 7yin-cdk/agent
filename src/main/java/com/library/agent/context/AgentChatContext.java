package com.library.agent.context;

import com.library.agent.entity.AgentLongTermMemory;
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
     * 用于检索召回的改写后问题；回答阶段仍以 query 作为用户原始问题。
     */
    private String rewrittenQuery;

    /**
     * 本轮问题识别出的意图类型。
     */
    private IntentType intentType;

    /**
     * 当前会话内召回的短期记忆消息。
     */
    private List<AgentShortTermMemory> historyMessages;

    /**
     * 当前会话摘要
     */
    private String conversationSummary;

    /**
     * 回答前召回的长期记忆（recall 填充），空列表时 Prompt 不注入该段
     */
    private List<AgentLongTermMemory> longTermMemories;
}
