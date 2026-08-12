package com.library.agent.llm;

import com.library.agent.context.AgentChatContext;
import com.library.agent.observability.ConversationTraceCollector;

public interface ToolCallingService {

    String chatWithTasks(AgentChatContext context, String prompt);

    /**
     * 带可观测采集器的 ReAct 对话。
     */
    String chatWithTasks(AgentChatContext context, String prompt, ConversationTraceCollector collector);
}
