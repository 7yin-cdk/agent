package com.library.agent.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.context.AgentChatContext;
import com.library.agent.llm.LlmExhaustedException;
import com.library.agent.llm.ToolCallingService;
import com.library.agent.llm.resilience.ChatModelFailoverOrchestrator;
import com.library.agent.observability.ConversationTraceCollector;
import com.library.agent.tool.ToolAccess;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolCallingServiceImpl implements ToolCallingService {

    private static final int MAX_REACT_STEPS = 10;
    private static final int MAX_CONSECUTIVE_TOOL_FAILURES = 3;
    /* 连续“工具调用被校验拦下”的次数上限：超过则放弃自纠错，返回面向用户的兜底文案 */
    private static final int MAX_CONSECUTIVE_GUARD_REJECTIONS = 3;
    private static final int REACT_HISTORY_LIMIT = 8;

    /**
     * 校验连续拦截且模型始终未能补齐参数时的兜底文案。
     * <p>
     * 不复用 {@link #validateToolAction} 的纠正文案：后者面向模型，含“参数来源标注”等内部措辞，
     * 直接返回给用户既泄露实现细节又不构成可执行答复。
     */
    private static final String GUARD_REJECTION_FALLBACK_MESSAGE =
            "抱歉，我没有执行任何工具或数据库操作：本次请求缺少完成任务所必需的参数信息。"
                    + "请补充说明要操作的目标（例如实例与库名）后重试。";
    /* TOOL_OUTPUT 落字校验用的历史工具输出拼接上限，防止超长 observation 撑大校验开销 */
    private static final int MAX_TOOL_OUTPUT_TEXT_LENGTH = 20000;
    private static final String APPLICATION_PACKAGE_PREFIX = "com.library.agent";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /* ReAct 使用 langchain4j ChatModel（对应百炼配置）；以下标识仅用于 trace 展示主模型，
     * 容错编排器降级到备用模型时该标识不代表实际命中的模型 */
    private static final String REACT_PROVIDER = "bailian";

    @Value("${agent.tool.timeout-seconds:30}")
    private int toolTimeoutSeconds;

    @Value("${bailian.chat-model:qwen-plus}")
    private String reactChatModel;

    /**
     * 工具执行专用线程池，用于 CompletableFuture 超时控制。
     * 使用守护线程避免阻塞 JVM 退出。
     */
    private final ExecutorService toolExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "tool-exec-");
        t.setDaemon(true);
        return t;
    });

    private final ChatModelFailoverOrchestrator chatOrchestrator;
    private final ApplicationContext applicationContext;

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

                /* 读取工具访问类型注解，未标注默认为只读 */
                ToolAccess toolAccess = method.getAnnotation(ToolAccess.class);
                ToolAccess.Type accessType = toolAccess == null
                        ? ToolAccess.Type.READ : toolAccess.value();

                ToolExecutor executor = new DefaultToolExecutor(bean, method);
                registeredTools.put(toolName, new RegisteredTool(specification, executor, accessType));
                log.info("Registered ReAct tool, name={}, method={}.{}",
                        toolName,
                        beanClass.getSimpleName(),
                        method.getName()
                );
            }
        }

        ToolSpecifications.validateSpecifications(toolSpecifications());
    }

    /**
     * 关闭工具执行线程池，释放线程资源。
     */
    @jakarta.annotation.PreDestroy
    public void shutdownToolExecutor() {
        toolExecutor.shutdown();
        try {
            if (!toolExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                toolExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            toolExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String chatWithTasks(AgentChatContext context, String reactPrompt) {
        if (registeredTools.isEmpty()) {
            return chatSingleUserMessage(userQuestion(context, reactPrompt));
        }
        List<ReActStep> steps = new ArrayList<>();
        Object memoryId = context == null ? null : context.getConversationId();
        String lastFailedToolName = null;
        int sameToolConsecutiveFailures = 0;
        int consecutiveGuardRejections = 0;
        /* 严格落字校验开关与用户本轮原话：用户对话链路开启，自动巡检（HealthCheckRunner）关闭 */
        boolean strict = context != null && context.isGroundingEnabled();
        String userQuery = userQuestion(context, reactPrompt);

        for (int stepNumber = 1; stepNumber <= MAX_REACT_STEPS; stepNumber++) {
            try {
                StringBuilder builder = new StringBuilder();
                appendReActHistory(builder, steps);
                String finalPrompt = reactPrompt.replace("{{react_history}}", builder.toString());
                log.info("ReAct step={} input length={}", stepNumber, finalPrompt.length());

                ChatResponse response = chatOrchestrator.chat(ChatRequest.builder()
                        .messages(buildReActMessages(finalPrompt))
                        .build());

                String modelOutput = response.aiMessage() == null ? null : response.aiMessage().text();
                log.info("ReAct step={} model output length={}", stepNumber,
                        modelOutput != null ? modelOutput.length() : 0);

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
                        return toolInfo + "，请稍后再试。";
                    }
                    String errorDetail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    ReActObservation parseErrorObs = new ReActObservation(false, null,
                            "Invalid JSON output: " + errorDetail
                                    + ". Please output exactly one valid JSON object following the required format.");
                    steps.add(new ReActStep(stepNumber, "Failed to parse model output", null, parseErrorObs));
                    continue;
                }

                if (decision.isFinish()) {
                    String answer = decision.finish().answer();
                    return answer == null || answer.isBlank()
                            ? "Tool task finished, but no answer was returned." : answer;
                }

                ToolCallValidationResult validationResult = validateToolAction(
                        decision.tool(), userQuery, strict, successfulObservationText(steps));
                if (!validationResult.ok()) {
                    log.warn("ReAct step={} tool={} rejected before execution: {}", stepNumber,
                            decision.tool().name(), validationResult.message());
                    consecutiveGuardRejections++;
                    if (consecutiveGuardRejections >= MAX_CONSECUTIVE_GUARD_REJECTIONS) {
                        return GUARD_REJECTION_FALLBACK_MESSAGE;
                    }
                    steps.add(new ReActStep(stepNumber, "Tool action rejected before execution",
                            decision.tool(), new ReActObservation(false, null,
                            buildGuardCorrection(validationResult.message()))));
                    continue;
                }
                consecutiveGuardRejections = 0;

                ToolExecutionRequest toolRequest = toToolExecutionRequest(decision.tool());
                ReActObservation observation = executeTool(toolRequest, memoryId);
                log.info("ReAct step={} tool={} result={}", stepNumber,
                        decision.tool().name(), toJson(observation));
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
                        return "工具 " + lastFailedToolName + " 暂时不可用，请稍后再试。";
                    }
                } else {
                    lastFailedToolName = null;
                    sameToolConsecutiveFailures = 0;
                }
            } catch (LlmExhaustedException e) {
                /* 全部模型重试/降级耗尽：直接返回友好文案，不再消耗剩余步骤预算，
                 * 避免烧完 10 步后返回"请描述清楚"这类把故障归咎于用户的误导提示 */
                log.error("ReAct step={} LLM exhausted, abort", stepNumber, e);
                return LlmExhaustedException.USER_FACING_MESSAGE;
            } catch (Exception e) {
                log.error("ReAct step {} failed", stepNumber, e);
            }
        }

        return "Too many ReAct steps. Please provide clearer conditions and try again.";
    }

    /**
     * 带可观测采集器的 ReAct 对话。
     * <p>
     * 在每个步骤中记录 LLM 调用（prompt、response、token）和工具调用（工具名、输入、输出）。
     */
    @Override
    public String chatWithTasks(AgentChatContext context, String reactPrompt,
                                ConversationTraceCollector collector) {
        if (registeredTools.isEmpty()) {
            return chatSingleUserMessage(userQuestion(context, reactPrompt));
        }
        List<ReActStep> steps = new ArrayList<>();
        Object memoryId = context == null ? null : context.getConversationId();
        String lastFailedToolName = null;
        int sameToolConsecutiveFailures = 0;
        int consecutiveGuardRejections = 0;
        /* 严格落字校验开关与用户本轮原话：用户对话链路开启，自动巡检（HealthCheckRunner）关闭 */
        boolean strict = context != null && context.isGroundingEnabled();
        String userQuery = userQuestion(context, reactPrompt);

        for (int stepNumber = 1; stepNumber <= MAX_REACT_STEPS; stepNumber++) {
            try {
                StringBuilder builder = new StringBuilder();
                appendReActHistory(builder, steps);
                String finalPrompt = reactPrompt.replace("{{react_history}}", builder.toString());
                log.info("ReAct step={} input length={}", stepNumber, finalPrompt.length());

                /* LLM 调用 */
                long llmStart = System.currentTimeMillis();
                ChatResponse response = chatOrchestrator.chat(ChatRequest.builder()
                        .messages(buildReActMessages(finalPrompt))
                        .build());
                long llmDuration = System.currentTimeMillis() - llmStart;

                String modelOutput = response.aiMessage() == null ? null : response.aiMessage().text();
                int outputTokens = response.tokenUsage() != null
                        ? response.tokenUsage().outputTokenCount() : 0;
                int inputTokens = response.tokenUsage() != null
                        ? response.tokenUsage().inputTokenCount() : 0;
                collector.recordLlmCall(REACT_PROVIDER + "/" + reactChatModel, "REACT_LLM",
                        finalPrompt, modelOutput, inputTokens, outputTokens, llmDuration);
                log.info("ReAct step={} model output length={}", stepNumber,
                        modelOutput != null ? modelOutput.length() : 0);

                ReActDecision decision;
                try {
                    decision = parseDecision(modelOutput);
                } catch (Exception e) {
                    log.warn("Failed to parse ReAct JSON, step={}", stepNumber, e);
                    sameToolConsecutiveFailures++;
                    if (sameToolConsecutiveFailures >= MAX_CONSECUTIVE_TOOL_FAILURES) {
                        return (lastFailedToolName != null
                                ? "工具 " + lastFailedToolName + " 暂时不可用" : "系统暂时无法处理您的请求") + "，请稍后再试。";
                    }
                    String errorDetail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    steps.add(new ReActStep(stepNumber, "Failed to parse model output", null,
                            new ReActObservation(false, null, "Invalid JSON output: " + errorDetail)));
                    continue;
                }

                if (decision.isFinish()) {
                    String answer = decision.finish().answer();
                    return answer == null || answer.isBlank()
                            ? "Tool task finished, but no answer was returned." : answer;
                }

                ToolCallValidationResult validationResult = validateToolAction(
                        decision.tool(), userQuery, strict, successfulObservationText(steps));
                if (!validationResult.ok()) {
                    /* 被拦下的动作不计入工具调用记录（工具并未执行），只作为失败 observation 回灌模型 */
                    log.warn("ReAct step={} tool={} rejected before execution: {}", stepNumber,
                            decision.tool().name(), validationResult.message());
                    consecutiveGuardRejections++;
                    if (consecutiveGuardRejections >= MAX_CONSECUTIVE_GUARD_REJECTIONS) {
                        return GUARD_REJECTION_FALLBACK_MESSAGE;
                    }
                    steps.add(new ReActStep(stepNumber, "Tool action rejected before execution",
                            decision.tool(), new ReActObservation(false, null,
                            buildGuardCorrection(validationResult.message()))));
                    continue;
                }
                consecutiveGuardRejections = 0;

                /* 工具调用 */
                ToolExecutionRequest toolRequest = toToolExecutionRequest(decision.tool());
                long toolStart = System.currentTimeMillis();
                ReActObservation observation = executeTool(toolRequest, memoryId);
                long toolDuration = System.currentTimeMillis() - toolStart;

                collector.recordToolCall(
                        decision.tool().name(),
                        actionToJson(decision.tool()),
                        observation.content(),
                        observation.success(),
                        toolDuration);

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
                        return "工具 " + lastFailedToolName + " 暂时不可用，请稍后再试。";
                    }
                } else {
                    lastFailedToolName = null;
                    sameToolConsecutiveFailures = 0;
                }
            } catch (LlmExhaustedException e) {
                /* 全部模型重试/降级耗尽：直接返回友好文案，不再消耗剩余步骤预算，
                 * 避免烧完 10 步后返回"请描述清楚"这类把故障归咎于用户的误导提示 */
                log.error("ReAct step={} LLM exhausted, abort", stepNumber, e);
                return LlmExhaustedException.USER_FACING_MESSAGE;
            } catch (Exception e) {
                log.error("ReAct step {} failed", stepNumber, e);
            }
        }

        return "Too many ReAct steps. Please provide clearer conditions and try again.";
    }

    /**
     * 单条 user 消息的对话调用（等价于 langchain4j {@code ChatModel.chat(String)} 语义）。
     * <p>
     * 经容错编排器发起，从而获得与主链路一致的重试、降级备用模型与熔断能力；
     * 模型全部不可用时抛 {@code LlmExhaustedException}，由调用方决定兜底文案。
     */
    private String chatSingleUserMessage(String text) {
        ChatResponse response = chatOrchestrator.chat(ChatRequest.builder()
                .messages(UserMessage.from(text))
                .build());
        return response.aiMessage() == null ? null : response.aiMessage().text();
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

        /* 可选字段：指代参数的候选对象（{argName: [candidate,...] }），缺省为空对象 */
        JsonNode argumentCandidatesNode = toolNode.get("argument_candidates");
        String argumentCandidates = argumentCandidatesNode == null || argumentCandidatesNode.isNull()
                ? "{}"
                : OBJECT_MAPPER.writeValueAsString(argumentCandidatesNode);
        return new ReActToolAction(name, arguments, argumentSources, argumentCandidates);
    }

    private ToolExecutionRequest toToolExecutionRequest(ReActToolAction action) {
        return ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString())
                .name(action.name())
                .arguments(action.arguments())
                .build();
    }

    /**
     * 校验工具调用动作是否可放行执行。
     * <p>
     * 结构性错误（不可解析、键不一致、非法来源枚举）立即拒绝；对必填参数，
     * 在严格落字校验（strict=true，用户对话链路）下依据
     * {@link ToolCallGuard}：参数值须出现在用户本轮原话，写工具不允许按指代放行，
     * 只读工具的指代参数在候选唯一且与选中值一致时方可放行，
     * 只读工具的 TOOL_OUTPUT 参数须落字于本轮此前成功工具的返回结果（支持链式下钻）。
     * 所有“需用户澄清/补齐”的问题聚合成一条消息返回，避免多参数时反复追问。
     *
     * @param action          LLM 输出的工具调用
     * @param userQuery       用户本轮原话，用于落字校验
     * @param strict          是否启用严格落字校验（自动巡检链路为 false，维持旧语义）
     * @param priorToolOutput 本轮此前成功工具调用的输出拼接文本，供 TOOL_OUTPUT 来源校验
     */
    private ToolCallValidationResult validateToolAction(ReActToolAction action,
                                                        String userQuery,
                                                        boolean strict,
                                                        String priorToolOutput) {

        /* 1. Tool Action不能为空 */
        if (action == null) {
            return ToolCallValidationResult.reject("Tool action is missing.");
        }

        /* 2. 解析 arguments */
        JsonNode arguments = readObjectNode(action.arguments(), "tool.arguments");

        /* 3. 解析 argument_sources */
        JsonNode argumentSources =
                readObjectNode(action.argumentSources(), "tool.argument_sources");

        /* 4. arguments 与 argument_sources必须拥有完全相同的key */
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

        /* 5. 容错解析候选对象（可选字段，坏 JSON 降级为空对象） */
        JsonNode candidateSources = readCandidates(action.argumentCandidates());

        /* 6. 工具访问类型（读/写） */
        RegisteredTool registeredTool = registeredTools.get(action.name());
        ToolAccess.Type access = registeredTool == null
                ? ToolAccess.Type.READ : registeredTool.accessType();

        /* 7. 校验必填参数，聚合需用户澄清/补齐的追问 */
        List<String> asks = new ArrayList<>();
        for (String requiredArgument : requiredArgumentNames(action.name())) {

            JsonNode argumentValue = arguments.get(requiredArgument);

            /* 参数不存在或为空字符串 */
            if (argumentValue == null || argumentValue.isNull()
                    || (argumentValue.isValueNode() && argumentValue.asText("").isBlank())) {
                asks.add(missingArgumentMessage(requiredArgument));
                continue;
            }

            /* 校验必填参数的source */
            JsonNode sourceNode = argumentSources.get(requiredArgument);
            if (sourceNode == null || sourceNode.isNull() || sourceNode.asText("").isBlank()) {
                asks.add(missingSourceMessage(requiredArgument));
                continue;
            }

            String source = sourceNode.asText("").trim().toUpperCase();

            /* source必须是合法枚举值 */
            if (!source.equals("EXPLICIT_CURRENT")
                    && !source.equals("REFERENCED_CURRENT")
                    && !source.equals(ToolCallGuard.SOURCE_TOOL_OUTPUT)
                    && !source.equals("HISTORY_ONLY")) {
                return ToolCallValidationResult.reject(
                        "Invalid argument source for parameter: "
                                + requiredArgument
                                + ". Allowed values are "
                                + "[EXPLICIT_CURRENT, REFERENCED_CURRENT, TOOL_OUTPUT, HISTORY_ONLY]."
                );
            }

            /* 提取参数值文本；对象/数组等非标量值无法落字，交由严格校验判定 */
            String valueText = argumentValue.isTextual()
                    ? argumentValue.asText()
                    : argumentValue.isValueNode() ? argumentValue.asText("") : "";

            String ask = ToolCallGuard.evaluate(
                    strict, access, source, requiredArgument, valueText,
                    userQuery, candidatesFor(requiredArgument, candidateSources), priorToolOutput);
            if (ask != null) {
                asks.add(ask);
            }
        }

        /* 全部通过或存在需澄清项 */
        if (!asks.isEmpty()) {
            return ToolCallValidationResult.reject(String.join("\n", asks));
        }
        return ToolCallValidationResult.allow();
    }

    /**
     * 读取某参数在 LLM 输出的 argument_candidates 中的候选对象列表。
     */
    private List<String> candidatesFor(String argumentName, JsonNode candidateSources) {
        if (candidateSources == null) {
            return List.of();
        }
        JsonNode node = candidateSources.get(argumentName);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isValueNode() && !item.asText("").isBlank()) {
                candidates.add(item.asText());
            }
        }
        return candidates;
    }

    /**
     * 容错解析 argument_candidates JSON；缺失或格式非法时降级为空对象，
     * 由严格校验的“无法确认指代 → 追问”分支兜底。
     */
    private JsonNode readCandidates(String json) {
        if (json == null || json.isBlank()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            return node != null && node.isObject() ? node : OBJECT_MAPPER.createObjectNode();
        } catch (Exception e) {
            log.warn("Failed to parse tool.argument_candidates, degraded to empty: {}", json);
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    /**
     * 把校验拦截信息包装成回灌给模型的失败 observation。
     * <p>
     * 校验消息本身面向模型（含“参数来源标注”等内部措辞），只能进 ReAct 历史，不能作为用户回答；
     * 这里补上明确的后续动作指引：要么按用户本轮原话补齐参数后重试，要么改用 finish 向用户追问，
     * 严禁编造取值。措辞与解析失败分支的 observation 保持一致，均为英文。
     *
     * @param rejectMessage {@link #validateToolAction} 返回的拦截原因
     * @return 供模型阅读的纠正说明
     */
    private String buildGuardCorrection(String rejectMessage) {
        return rejectMessage + "\n"
                + "The tool call was NOT executed. Do one of the following instead: "
                + "(a) call the tool again with every required argument grounded in the user's current message, "
                + "(b) output a finish action whose answer asks the user for the missing value in plain language. "
                + "Never invent or guess an argument value.";
    }

    private String missingArgumentMessage(String argumentName) {
        return "缺少必填工具参数「" + argumentName + "」，请在本轮提问中提供该参数的准确值。";
    }

    private String missingSourceMessage(String argumentName) {
        return "工具参数「" + argumentName
                + "」缺少参数来源标注，无法确认其由用户在本轮提供，请明确给出该参数取值。";
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

    private ReActObservation executeTool(ToolExecutionRequest toolRequest, Object memoryId) {
        RegisteredTool registeredTool = registeredTools.get(toolRequest.name());
        if (registeredTool == null) {
            return new ReActObservation(false, null, "Unregistered tool: " + toolRequest.name());
        }

        try {
            String result = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return registeredTool.executor().execute(toolRequest, memoryId);
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    }, toolExecutor)
                    .orTimeout(toolTimeoutSeconds, TimeUnit.SECONDS)
                    .join();
            return new ReActObservation(true, result, null);
        } catch (CancellationException e) {
            log.warn("Tool execution cancelled, name={}", toolRequest.name());
            return new ReActObservation(false, null,
                    "工具 " + toolRequest.name() + " 执行被取消");
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                log.warn("Tool execution timeout, name={}, timeout={}s, arguments={}",
                        toolRequest.name(), toolTimeoutSeconds, toolRequest.arguments());
                return new ReActObservation(false, null,
                        "工具 " + toolRequest.name() + " 执行超时（超过 "
                                + toolTimeoutSeconds + " 秒），请检查目标服务状态后重试");
            }
            String error = cause != null && cause.getMessage() != null
                    ? cause.getMessage()
                    : cause != null ? cause.getClass().getSimpleName() : e.getClass().getSimpleName();
            log.warn("Tool execution failed, name={}, arguments={}",
                    toolRequest.name(), toolRequest.arguments(), cause != null ? cause : e);
            return new ReActObservation(false, null, error);
        }
    }

    private String userQuestion(AgentChatContext context, String prompt) {
        if (context != null && context.getQuery() != null && !context.getQuery().isBlank()) {
            return context.getQuery().trim();
        }
        return prompt == null ? "" : prompt.trim();
    }

    /**
     * 汇总本轮此前“成功”工具调用的输出内容，供 TOOL_OUTPUT 落字校验使用。
     * <p>
     * 只取 {@code observation.success()} 为真且 content 非空的步骤，因此失败的调用
     * 永远无法为后续参数背书；仅回看最近 {@link #REACT_HISTORY_LIMIT} 步，并设总长上限，
     * 避免超长 observation 拼成巨型字符串反复参与子串匹配。
     *
     * @param steps 本轮 ReAct 步骤列表
     * @return 拼接后的输出文本；无可用输出时返回空串
     */
    private String successfulObservationText(List<ReActStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        int fromIndex = Math.max(0, steps.size() - REACT_HISTORY_LIMIT);
        StringBuilder builder = new StringBuilder();
        for (int i = fromIndex; i < steps.size(); i++) {
            ReActObservation observation = steps.get(i).observation();
            if (observation == null || !observation.success() || observation.content() == null) {
                continue;
            }
            if (builder.length() >= MAX_TOOL_OUTPUT_TEXT_LENGTH) {
                break;
            }
            builder.append(observation.content()).append('\n');
        }
        return builder.length() > MAX_TOOL_OUTPUT_TEXT_LENGTH
                ? builder.substring(0, MAX_TOOL_OUTPUT_TEXT_LENGTH)
                : builder.toString();
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
            actionMap.put("argument_candidates", OBJECT_MAPPER.readTree(action.argumentCandidates()));
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

    private record RegisteredTool(ToolSpecification specification, ToolExecutor executor,
                                  ToolAccess.Type accessType) {
    }

    private record ReActDecision(String type, String thought, ReActToolAction tool, ReActFinish finish) {

        private boolean isFinish() {
            return "finish".equals(type);
        }
    }

    private record ReActToolAction(String name, String arguments, String argumentSources,
                                   String argumentCandidates) {
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
