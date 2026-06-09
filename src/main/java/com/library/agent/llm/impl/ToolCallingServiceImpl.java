package com.library.agent.llm.impl;

import com.library.agent.context.AgentChatContext;
import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.llm.ToolCallingService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolCallingServiceImpl implements ToolCallingService {

    private static final int MAX_TOOL_CALL_ROUNDS = 5;
    private static final String APPLICATION_PACKAGE_PREFIX = "com.library.agent";

    private final ChatModel chatModel;
    private final ApplicationContext applicationContext;

    private final Map<String, RegisteredTool> registeredTools = new LinkedHashMap<>();

    /**
     * 在spring容器初始化时注册工具
     */
    @PostConstruct
    public void registerTools() {
        Map<String, Object> beans = applicationContext.getBeansOfType(Object.class, false, false);
        for (Object bean : beans.values()) {
            Class<?> beanClass = bean.getClass();
            Package beanPackage = beanClass.getPackage();
            if (beanPackage == null || !beanPackage.getName().startsWith(APPLICATION_PACKAGE_PREFIX)) {
                continue;
            }

            for (Method method : beanClass.getMethods()) {
                if (!method.isAnnotationPresent(Tool.class)) {
                    continue;
                }

                ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
                String toolName = specification.name();
                if (registeredTools.containsKey(toolName)) {
                    throw new IllegalStateException("Duplicate tool name: " + toolName);
                }

                ToolExecutor executor = new DefaultToolExecutor(bean, method);
                registeredTools.put(toolName, new RegisteredTool(specification, executor));
                log.info("Registered LangChain4j tool, name={}, method={}.{}",
                        toolName,
                        beanClass.getSimpleName(),
                        method.getName()
                );
            }
        }

        ToolSpecifications.validateSpecifications(toolSpecifications());
    }

    @Override
    public String chatWithTools(AgentChatContext context, String prompt) {
        if (registeredTools.isEmpty()) {
            return chatModel.chat(prompt);
        }

        List<ChatMessage> messages = buildInitialMessages(context, prompt);
        Object memoryId = context == null ? null : context.getConversationId();

        for (int round = 0; round < MAX_TOOL_CALL_ROUNDS; round++) {
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(toolSpecifications())
                    .build());

            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            if (aiMessage == null || !aiMessage.hasToolExecutionRequests()) {
                String answer = aiMessage == null ? null : aiMessage.text();
                return answer == null || answer.isBlank() ? "Tool calls completed, but the model did not return a valid answer." : answer;
            }

            for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                String toolResult = executeTool(toolRequest, memoryId);
                messages.add(ToolExecutionResultMessage.from(toolRequest, toolResult));
            }
        }

        return "Too many tool call rounds. Please provide clearer conditions and try again.";
    }

    private List<ChatMessage> buildInitialMessages(AgentChatContext context, String prompt) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("You are an assistant that can call registered tools when needed. If a tool result is required, call the tool first. After the tool returns, answer the user using the real tool result. Do not invent tool results."));

        if (context != null && context.getHistoryMessages() != null) {
            for (AgentShortTermMemory message : context.getHistoryMessages()) {
                ChatMessage chatMessage = toChatMessage(message);
                if (chatMessage != null) {
                    messages.add(chatMessage);
                }
            }
        }

        messages.add(UserMessage.from(prompt == null ? "" : prompt));
        return messages;
    }

    private ChatMessage toChatMessage(AgentShortTermMemory message) {
        if (message == null || message.getContent() == null || message.getContent().isBlank()) {
            return null;
        }

        String role = message.getRole() == null ? "" : message.getRole().trim().toLowerCase();
        String content = message.getContent().trim();
        return switch (role) {
            case "user" -> UserMessage.from(content);
            case "assistant" -> AiMessage.from(content);
            default -> null;
        };
    }

    private String executeTool(ToolExecutionRequest toolRequest, Object memoryId) {
        RegisteredTool registeredTool = registeredTools.get(toolRequest.name());
        if (registeredTool == null) {
            return "Tool call failed: unregistered tool " + toolRequest.name();
        }

        try {
            return registeredTool.executor().execute(toolRequest, memoryId);
        } catch (Exception e) {
            log.warn("Tool execution failed, name={}, arguments={}", toolRequest.name(), toolRequest.arguments(), e);
            return "Tool call failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private List<ToolSpecification> toolSpecifications() {
        return registeredTools.values().stream()
                .map(RegisteredTool::specification)
                .toList();
    }

    private record RegisteredTool(ToolSpecification specification, ToolExecutor executor) {
    }
}
