package com.library.agent.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.context.AgentChatContext;
import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.llm.ToolCallingService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
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
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolCallingServiceImpl implements ToolCallingService {

    private static final int MAX_REACT_STEPS = 5;
    private static final int REACT_HISTORY_LIMIT = 8;
    private static final String APPLICATION_PACKAGE_PREFIX = "com.library.agent";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final ApplicationContext applicationContext;

    private final Map<String, RegisteredTool> registeredTools = new LinkedHashMap<>();

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
                log.info("Registered ReAct tool, name={}, method={}.{}",
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
            return chatModel.chat(userQuestion(context, prompt));
        }

        List<ReActStep> steps = new ArrayList<>();
        Object memoryId = context == null ? null : context.getConversationId();

        for (int stepNumber = 1; stepNumber <= MAX_REACT_STEPS; stepNumber++) {
            String reactPrompt = buildReActPrompt(context, prompt, steps);
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(buildReActMessages(reactPrompt))
                    .build());

            String modelOutput = response.aiMessage() == null ? null : response.aiMessage().text();
            ReActDecision decision = parseDecision(modelOutput);
            log.info("ReAct decision, step={}, type={}, thought={}", stepNumber, decision.type(), decision.thought());

            if (decision.isFinish()) {
                String answer = decision.finish().answer();
                return answer == null || answer.isBlank() ? "Tool task finished, but no answer was returned." : answer;
            }

            ToolExecutionRequest toolRequest = toToolExecutionRequest(decision.tool());
            ReActObservation observation = executeTool(toolRequest, memoryId);
            steps.add(new ReActStep(stepNumber, decision.thought(), decision.tool(), observation));
        }

        return "Too many ReAct steps. Please provide clearer conditions and try again.";
    }

    private List<ChatMessage> buildReActMessages(String reactPrompt) {
        return List.of(
                SystemMessage.from("""
                        You are a ReAct tool orchestration model.
                        You must output exactly one valid JSON object and no markdown.
                        Each round must choose either a tool action or a final answer.
                        The fields tool and finish are mutually exclusive.
                        Do not invent tool results. Use only observations shown in the prompt.
                        """),
                UserMessage.from(reactPrompt)
        );
    }

    private String buildReActPrompt(AgentChatContext context, String prompt, List<ReActStep> steps) {
        StringBuilder builder = new StringBuilder();
        builder.append("### Task\n");
        builder.append("Decide the next ReAct step for the user request.\n\n");

        builder.append("### Current user question\n");
        builder.append(userQuestion(context, prompt)).append("\n\n");

        builder.append("### Conversation summary\n");
        String conversationSummary = context == null ? null : context.getConversationSummary();
        builder.append(conversationSummary == null || conversationSummary.isBlank() ? "None" : conversationSummary.trim()).append("\n\n");

        builder.append("### Recent conversation history\n");
        appendHistory(builder, context == null ? null : context.getHistoryMessages());

        builder.append("### Available tools\n");
        appendToolCatalog(builder);

        builder.append("### ReAct history\n");
        appendReActHistory(builder, steps);

        builder.append("### Required output JSON\n");
        builder.append("""
                Return exactly one JSON object using one of these two forms.

                Tool action:
                {
                  "type": "tool",
                  "thought": "brief reason for the next action",
                  "tool": {
                    "name": "registered tool name",
                    "arguments": {
                      "argumentName": "argumentValue"
                    }
                  },
                  "finish": null
                }

                Final answer:
                {
                  "type": "finish",
                  "thought": "brief reason why the task can be finished",
                  "tool": null,
                  "finish": {
                    "answer": "final answer to the user"
                  }
                }

                Rules:
                - Use type=tool when a registered tool is needed.
                - Use type=finish when enough information is available, or when required parameters are missing and the user must clarify.
                - tool.name must be one of Available tools.
                - tool.arguments must match the selected tool schema.
                - Do not include markdown fences or text outside the JSON object.
                """);
        return builder.toString();
    }

    private void appendHistory(StringBuilder builder, List<AgentShortTermMemory> historyMessages) {
        List<AgentShortTermMemory> limitedHistory = limitHistory(historyMessages);
        if (limitedHistory.isEmpty()) {
            builder.append("None\n\n");
            return;
        }

        for (AgentShortTermMemory message : limitedHistory) {
            if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            builder.append("- ")
                    .append(normalizeRole(message.getRole()))
                    .append(": ")
                    .append(message.getContent().trim())
                    .append("\n");
        }
        builder.append("\n");
    }

    private void appendToolCatalog(StringBuilder builder) {
        if (registeredTools.isEmpty()) {
            builder.append("None\n\n");
            return;
        }

        for (RegisteredTool registeredTool : registeredTools.values()) {
            ToolSpecification specification = registeredTool.specification();
            builder.append("- name: ").append(specification.name()).append("\n");
            builder.append("  description: ").append(specification.description()).append("\n");
            builder.append("  schema: ").append(specification.toJson()).append("\n");
        }
        builder.append("\n");
    }

    private void appendReActHistory(StringBuilder builder, List<ReActStep> steps) {
        if (steps == null || steps.isEmpty()) {
            builder.append("None\n\n");
            return;
        }

        for (ReActStep step : steps) {
            builder.append("Step ").append(step.stepNumber()).append("\n");
            builder.append("thought: ").append(nullToEmpty(step.thought())).append("\n");
            builder.append("action: ").append(actionToJson(step.action())).append("\n");
            builder.append("observation: ").append(toJson(step.observation())).append("\n");
        }
        builder.append("\n");
    }

    private ReActDecision parseDecision(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            throw new IllegalStateException("ReAct model returned an empty response");
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(extractJsonObject(modelOutput));
            String type = root.path("type").asText("").trim().toLowerCase();
            String thought = root.path("thought").asText("");
            JsonNode toolNode = root.get("tool");
            JsonNode finishNode = root.get("finish");

            if ("tool".equals(type)) {
                if (toolNode == null || toolNode.isNull() || (finishNode != null && !finishNode.isNull())) {
                    throw new IllegalArgumentException("type=tool requires tool and finish=null");
                }
                ReActToolAction action = parseToolAction(toolNode);
                if (!registeredTools.containsKey(action.name())) {
                    throw new IllegalArgumentException("Unregistered tool: " + action.name());
                }
                return new ReActDecision(type, thought, action, null);
            }

            if ("finish".equals(type)) {
                if (finishNode == null || finishNode.isNull() || (toolNode != null && !toolNode.isNull())) {
                    throw new IllegalArgumentException("type=finish requires finish and tool=null");
                }
                String answer = finishNode.path("answer").asText("");
                return new ReActDecision(type, thought, null, new ReActFinish(answer));
            }

            throw new IllegalArgumentException("Unknown ReAct type: " + type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse ReAct JSON: " + modelOutput, e);
        }
    }

    private ReActToolAction parseToolAction(JsonNode toolNode) throws Exception {
        String name = toolNode.path("name").asText("").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("tool.name is required");
        }

        JsonNode argumentsNode = toolNode.get("arguments");
        String arguments = argumentsNode == null || argumentsNode.isNull()
                ? "{}"
                : OBJECT_MAPPER.writeValueAsString(argumentsNode);
        return new ReActToolAction(name, arguments);
    }

    private ToolExecutionRequest toToolExecutionRequest(ReActToolAction action) {
        return ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString())
                .name(action.name())
                .arguments(action.arguments())
                .build();
    }

    private ReActObservation executeTool(ToolExecutionRequest toolRequest, Object memoryId) {
        RegisteredTool registeredTool = registeredTools.get(toolRequest.name());
        if (registeredTool == null) {
            return new ReActObservation(false, null, "Unregistered tool: " + toolRequest.name());
        }

        try {
            String result = registeredTool.executor().execute(toolRequest, memoryId);
            return new ReActObservation(true, result, null);
        } catch (Exception e) {
            log.warn("Tool execution failed, name={}, arguments={}", toolRequest.name(), toolRequest.arguments(), e);
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new ReActObservation(false, null, error);
        }
    }

    private String userQuestion(AgentChatContext context, String prompt) {
        if (context != null && context.getQuery() != null && !context.getQuery().isBlank()) {
            return context.getQuery().trim();
        }
        return prompt == null ? "" : prompt.trim();
    }

    private List<AgentShortTermMemory> limitHistory(List<AgentShortTermMemory> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return List.of();
        }
        int fromIndex = Math.max(0, historyMessages.size() - REACT_HISTORY_LIMIT);
        return historyMessages.subList(fromIndex, historyMessages.size());
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "unknown";
        }
        return switch (role.trim().toLowerCase()) {
            case "user" -> "user";
            case "assistant" -> "assistant";
            case "tool" -> "tool";
            case "system" -> "system";
            default -> role.trim();
        };
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String actionToJson(ReActToolAction action) {
        if (action == null) {
            return "null";
        }

        try {
            JsonNode arguments = OBJECT_MAPPER.readTree(action.arguments());
            Map<String, Object> actionMap = new LinkedHashMap<>();
            actionMap.put("name", action.name());
            actionMap.put("arguments", arguments);
            return OBJECT_MAPPER.writeValueAsString(actionMap);
        } catch (Exception e) {
            return toJson(action);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private List<ToolSpecification> toolSpecifications() {
        return registeredTools.values().stream()
                .map(RegisteredTool::specification)
                .toList();
    }

    private record RegisteredTool(ToolSpecification specification, ToolExecutor executor) {
    }

    private record ReActDecision(String type, String thought, ReActToolAction tool, ReActFinish finish) {

        private boolean isFinish() {
            return "finish".equals(type);
        }
    }

    private record ReActToolAction(String name, String arguments) {
    }

    private record ReActFinish(String answer) {
    }

    private record ReActStep(int stepNumber, String thought, ReActToolAction action, ReActObservation observation) {
    }

    private record ReActObservation(boolean success, String content, String error) {
    }
}
