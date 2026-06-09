package com.library.agent.llm;

import com.library.agent.context.AgentChatContext;

public interface ToolCallingService {

    String chatWithTools(AgentChatContext context, String prompt);
}
