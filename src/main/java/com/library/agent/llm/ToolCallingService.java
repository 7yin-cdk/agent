package com.library.agent.llm;

import com.library.agent.context.AgentChatContext;
import com.library.agent.observability.ConversationTraceCollector;

import java.util.List;

public interface ToolCallingService {

    String chatWithTasks(AgentChatContext context, String prompt);

    /**
     * 带可观测采集器的 ReAct 对话。
     */
    String chatWithTasks(AgentChatContext context, String prompt, ConversationTraceCollector collector);

    /**
     * 导出已注册工具的 schema，供测评脚本校验工具调用参数的类型与必填性。
     */
    List<ToolSchemaView> describeRegisteredTools();
}
