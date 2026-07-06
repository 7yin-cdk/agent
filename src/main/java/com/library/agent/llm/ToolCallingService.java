package com.library.agent.llm;

import com.library.agent.context.AgentChatContext;

public interface ToolCallingService {

    String chatWithTasks(AgentChatContext context, String prompt);
}
