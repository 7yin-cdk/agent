package com.library.agent.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.context.AgentChatContext;
import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.llm.ToolCallingService;
import com.library.agent.tracing.TracingConstant;
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
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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

    private static final int MAX_REACT_STEPS = 10;
    private static final int MAX_CONSECUTIVE_TOOL_FAILURES = 3;
    private static final int REACT_HISTORY_LIMIT = 8;
    private static final String APPLICATION_PACKAGE_PREFIX = "com.library.agent";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final ApplicationContext applicationContext;
    private final Tracer tracer;

    private final Map<String, RegisteredTool> registeredTools = new LinkedHashMap<>();

    /**
     * 在Spring容器启动前注册工具
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
    public String chatWithTasks(AgentChatContext context, String reactPrompt) {
        if (registeredTools.isEmpty()) {
            return chatModel.chat(userQuestion(context, reactPrompt));
        }
        List<ReActStep> steps = new ArrayList<>();
        Object memoryId = context == null ? null : context.getConversationId();
        String lastFailedToolName = null;
        int sameToolConsecutiveFailures = 0;

        for (int stepNumber = 1; stepNumber <= MAX_REACT_STEPS; stepNumber++) {
            Span stepSpan = tracer.nextSpan()
                    .name(TracingConstant.REACT_STEP_PREFIX + "-" + stepNumber)
                    .start();

            try (Tracer.SpanInScope stepScope = tracer.withSpan(stepSpan)) {
                StringBuilder builder = new StringBuilder();
                appendReActHistory(builder, steps);
                String finalPrompt = reactPrompt.replace("{{react_history}}", builder.toString());
                log.info("""

                        ==================== ReAct NEXT INPUT step={} ====================
                        {}
                        ================== END ReAct NEXT INPUT step={} ==================
                        """, stepNumber, finalPrompt, stepNumber);

                /* LLM 调用子 Span */
                Span llmSpan = tracer.nextSpan()
                        .name(TracingConstant.REACT_LLM_CALL)
                        .start();
                ChatResponse response;
                try (Tracer.SpanInScope llmScope = tracer.withSpan(llmSpan)) {
                    response = chatModel.chat(ChatRequest.builder()
                            .messages(buildReActMessages(finalPrompt))
                            .build());
                } finally {
                    llmSpan.end();
                }

                String modelOutput = response.aiMessage() == null ? null : response.aiMessage().text();
                log.info("""

                        =================== ReAct MODEL OUTPUT step={} ===================
                        {}
                        ================= END ReAct MODEL OUTPUT step={} =================
                        """, stepNumber, modelOutput, stepNumber);

                ReActDecision decision;
                try {
                    decision = parseDecision(modelOutput);
                } catch (Exception e) {
                    log.warn("Failed to parse ReAct JSON, step={}", stepNumber, e);
                    sameToolConsecutiveFailures++;
                    if (sameToolConsecutiveFailures >= MAX_CONSECUTIVE_TOOL_FAILURES) {
                        String toolInfo = lastFailedToolName != null
                                ? "工具 " + lastFailedToolName + " 暂时不可用"
                                : "系统暂时无法处理您的请求";
                        stepSpan.tag("react.outcome", "error");
                        return toolInfo + "，请稍后再试。";
                    }
                    String errorDetail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    ReActObservation parseErrorObs = new ReActObservation(false, null,
                            "Invalid JSON output: " + errorDetail
                                    + ". Please output exactly one valid JSON object following the required format.");
                    steps.add(new ReActStep(stepNumber, "Failed to parse model output", null, parseErrorObs));
                    continue;
                }
                log.info("ReAct decision, step={}, type={}, thought={}", stepNumber, decision.type(), decision.thought());

                if (decision.isFinish()) {
                    stepSpan.tag("react.outcome", "finish");
                    String answer = decision.finish().answer();
                    return answer == null || answer.isBlank()
                            ? "Tool task finished, but no answer was returned." : answer;
                }

                ToolCallValidationResult validationResult = validateToolAction(decision.tool());
                if (!validationResult.ok()) {
                    stepSpan.tag("react.outcome", "validation_failed");
                    return validationResult.message();
                }

                ToolExecutionRequest toolRequest = toToolExecutionRequest(decision.tool());
                Span toolSpan = tracer.nextSpan()
                        .name(TracingConstant.REACT_TOOL_PREFIX + "." + decision.tool().name())
                        .start();
                ReActObservation observation;
                try (Tracer.SpanInScope toolScope = tracer.withSpan(toolSpan)) {
                    observation = executeTool(toolRequest, memoryId);
                    toolSpan.tag("tool.success", String.valueOf(observation.success()));
                } finally {
                    toolSpan.end();
                }
                log.info("""

                        ================= ReAct TOOL OBSERVATION step={} =================
                        action: {}
                        observation: {}
                        =============== END ReAct TOOL OBSERVATION step={} ===============
                        """, stepNumber, actionToJson(decision.tool()), toJson(observation), stepNumber);
                steps.add(new ReActStep(stepNumber, decision.thought(), decision.tool(), observation));

                if (!observation.success()) {
                    String currentToolName = decision.tool().name();
                    if (currentToolName.equals(lastFailedToolName)) {
                        sameToolConsecutiveFailures++;
                    } else {
                        lastFailedToolName = currentToolName;
                        sameToolConsecutiveFailures = 1;
                    }
                    if (sameToolConsecutiveFailures >= MAX_CONSECUTIVE_TOOL_FAILURES) {
                        stepSpan.tag("react.outcome", "tool_error");
                        return "工具 " + lastFailedToolName + " 暂时不可用，请稍后再试。";
                    }
                } else {
                    lastFailedToolName = null;
                    sameToolConsecutiveFailures = 0;
                }
            } finally {
                stepSpan.end();
            }
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
        builder.append("Decide the next ReAct step for the user request.Tools can only be invoked with parameters explicitly provided by the user.\n\n");

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
                    },
                    "argument_sources": {
                      "argumentName": "EXPLICIT_CURRENT or REFERENCED_CURRENT or HISTORY_ONLY"
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
- Every key in tool.arguments must have the same key in tool.argument_sources.

- Each argument source must be exactly one of:
  - EXPLICIT_CURRENT
  - REFERENCED_CURRENT
  - HISTORY_ONLY

- Use EXPLICIT_CURRENT when the argument value is explicitly stated in the Current user question.

- Use REFERENCED_CURRENT when the argument value is not explicitly stated, but the Current user question contains a reference, pronoun, or other expression that clearly refers to the value.
  Examples:
  - History: "北京天气怎么样"
    Current: "那它明天呢"
    city -> REFERENCED_CURRENT
  - History: "介绍一下Spring Boot"
    Current: "它有什么优缺点"
    topic -> REFERENCED_CURRENT

- Use HISTORY_ONLY when the argument value is not mentioned or referenced in the Current user question and can only be obtained from Conversation summary or Recent conversation history.

- A parameter is considered provided by the user in the current turn if its source is EXPLICIT_CURRENT or REFERENCED_CURRENT.

- A parameter is NOT considered provided by the user in the current turn if its source is HISTORY_ONLY.

- If any required tool parameter is HISTORY_ONLY, do not invoke the tool. Instead, use type=finish and ask the user to explicitly provide or confirm the missing parameter.

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

        JsonNode argumentSourcesNode = toolNode.get("argument_sources");
        String argumentSources = argumentSourcesNode == null || argumentSourcesNode.isNull()
                ? "{}"
                : OBJECT_MAPPER.writeValueAsString(argumentSourcesNode);
        return new ReActToolAction(name, arguments, argumentSources);
    }

    private ToolExecutionRequest toToolExecutionRequest(ReActToolAction action) {
        return ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString())
                .name(action.name())
                .arguments(action.arguments())
                .build();
    }

    private ToolCallValidationResult validateToolAction(ReActToolAction action) {

        // 1. Tool Action不能为空
        if (action == null) {
            return ToolCallValidationResult.reject(
                    "Tool action is missing."
            );
        }

        // 2. 解析 arguments
        JsonNode arguments =
                readObjectNode(action.arguments(), "tool.arguments");

        // 3. 解析 argument_sources
        JsonNode argumentSources =
                readObjectNode(action.argumentSources(), "tool.argument_sources");

        // 4. arguments 与 argument_sources必须拥有完全相同的key
        Map<String, JsonNode> argumentMap = new LinkedHashMap<>();
        arguments.fields().forEachRemaining(
                entry -> argumentMap.put(entry.getKey(), entry.getValue())
        );

        Map<String, JsonNode> sourceMap = new LinkedHashMap<>();
        argumentSources.fields().forEachRemaining(
                entry -> sourceMap.put(entry.getKey(), entry.getValue())
        );

        if (!argumentMap.keySet().equals(sourceMap.keySet())) {
            return ToolCallValidationResult.reject(
                    "tool.arguments and tool.argument_sources must contain exactly the same keys."
            );
        }

        // 5. 校验必填参数
        for (String requiredArgument : requiredArgumentNames(action.name())) {

            JsonNode argumentValue = arguments.get(requiredArgument);

            // 参数不存在
            if (argumentValue == null || argumentValue.isNull()) {
                return ToolCallValidationResult.reject(
                        clarificationMessage(requiredArgument)
                );
            }

            // 参数为空字符串
            if (argumentValue.isValueNode()
                    && argumentValue.asText("").isBlank()) {

                return ToolCallValidationResult.reject(
                        clarificationMessage(requiredArgument)
                );
            }

            // 校验必填参数的source
            JsonNode sourceNode = argumentSources.get(requiredArgument);

            if (sourceNode == null
                    || sourceNode.isNull()
                    || sourceNode.asText("").isBlank()) {

                return ToolCallValidationResult.reject(
                        clarificationMessage(requiredArgument)
                );
            }

            String source =
                    sourceNode.asText("")
                            .trim()
                            .toUpperCase();

            // source必须是合法枚举值
            if (!source.equals("EXPLICIT_CURRENT")
                    && !source.equals("REFERENCED_CURRENT")
                    && !source.equals("HISTORY_ONLY")) {

                return ToolCallValidationResult.reject(
                        "Invalid argument source for parameter: "
                                + requiredArgument
                                + ". Allowed values are "
                                + "[EXPLICIT_CURRENT, REFERENCED_CURRENT, HISTORY_ONLY]."
                );
            }

            // 核心业务规则
            // required参数不能只来自历史
            if ("HISTORY_ONLY".equals(source)) {

                return ToolCallValidationResult.reject(
                        clarificationMessage(requiredArgument)
                );
            }
        }

        // 全部通过
        return ToolCallValidationResult.allow();
    }

    private List<String> requiredArgumentNames(String toolName) {
        RegisteredTool registeredTool = registeredTools.get(toolName);
        if (registeredTool == null || registeredTool.specification() == null) {
            return List.of();
        }

        try {
            JsonNode specificationJson = OBJECT_MAPPER.readTree(registeredTool.specification().toJson());
            JsonNode requiredNode = findFirstArrayField(specificationJson, "required");
            if (requiredNode == null || requiredNode.isEmpty()) {
                return List.of();
            }

            List<String> requiredNames = new ArrayList<>();
            for (JsonNode node : requiredNode) {
                if (node != null && node.isTextual() && !node.asText().isBlank()) {
                    requiredNames.add(node.asText());
                }
            }
            return requiredNames;
        } catch (Exception e) {
            log.warn("Failed to read required arguments from tool specification, tool={}", toolName, e);
            return List.of();
        }
    }

    private JsonNode findFirstArrayField(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode directValue = node.get(fieldName);
        if (directValue != null && directValue.isArray()) {
            return directValue;
        }
        if (node.isObject()) {
            for (var fields = node.fields(); fields.hasNext(); ) {
                JsonNode found = findFirstArrayField(fields.next().getValue(), fieldName);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findFirstArrayField(child, fieldName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private JsonNode readObjectNode(String json, String fieldName) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
            if (!node.isObject()) {
                throw new IllegalArgumentException(fieldName + " must be a JSON object");
            }
            return node;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse " + fieldName + ": " + json, e);
        }
    }

    private String normalizeForEvidence(String text) {
        if (text == null) {
            return "";
        }
        return text.trim()
                .replaceAll("\\s+", "")
                .toLowerCase();
    }

    private String clarificationMessage(String argumentName) {
        return "Missing required tool parameter from the current user input: " + argumentName
                + ". Please provide this parameter explicitly.";
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
            actionMap.put("argument_sources", OBJECT_MAPPER.readTree(action.argumentSources()));
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

    private record ReActToolAction(String name, String arguments, String argumentSources) {
    }

    private record ReActFinish(String answer) {
    }

    private record ReActStep(int stepNumber, String thought, ReActToolAction action, ReActObservation observation) {
    }

    private record ReActObservation(boolean success, String content, String error) {
    }

    private record ToolCallValidationResult(boolean ok, String message) {

        private static ToolCallValidationResult allow() {
            return new ToolCallValidationResult(true, null);
        }

        private static ToolCallValidationResult reject(String message) {
            return new ToolCallValidationResult(false, message);
        }
    }
}
