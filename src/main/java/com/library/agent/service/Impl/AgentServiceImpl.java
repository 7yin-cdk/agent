package com.library.agent.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.auth.context.UserContextHolder;
import com.library.agent.auth.context.UserContext;
import com.library.agent.context.AgentChatContext;
import com.library.agent.conversation.dto.ConversationResponse;
import com.library.agent.conversation.dto.CreateConversationRequest;
import com.library.agent.conversation.service.ConversationService;
import com.library.agent.dto.ChatRequest;
import com.library.agent.dto.ChatResponse;
import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.enums.IntentType;
import com.library.agent.llm.LlmService;
import com.library.agent.llm.PromptBuilder;
import com.library.agent.llm.QueryRewriteResult;
import com.library.agent.llm.QueryRewriteService;
import com.library.agent.llm.ToolCallingService;
import com.library.agent.memory.ConversationSummaryService;
import com.library.agent.memory.LongTermMemoryService;
import com.library.agent.memory.ShortTermMemoryService;
import com.library.agent.rag.service.RagService;
import com.library.agent.service.AgentService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Agent 聊天编排服务实现。
 * <p>
 * 该类负责串联登录用户、会话、短期记忆、意图识别、路径路由和消息写回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private static final ObjectMapper SSE_OBJECT_MAPPER = new ObjectMapper();

    /**
     * 回答阶段召回的短期记忆条数。
     */
    private static final int ANSWER_HISTORY_LIMIT = 20;

    /**
     * 意图识别阶段使用的短期记忆条数。
     */
    private static final int INTENT_HISTORY_LIMIT = 5;

    private final LlmService llmService;
    private final RagService ragService;
    private final ConversationService conversationService;
    private final ShortTermMemoryService shortTermMemoryService;
    private final ConversationSummaryService conversationSummaryService;
    private final ToolCallingService toolCallingService;
    private final QueryRewriteService queryRewriteService;
    private final LongTermMemoryService longTermMemoryService;
    private final Tracer tracer;

    /**
     * 执行带会话的 Agent 聊天。
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        //检验请求参数
        validateChatRequest(request);
        //获取当前登录用户id
        Long userId = requireCurrentUserId();
        String query = request.getQuery().trim();
        String conversationId = resolveConversationId(userId, request.getConversationId(), query);
        //获取用户当前会话历史消息
        List<AgentShortTermMemory> historyMessages = shortTermMemoryService.listRecentMessages(
                userId,
                conversationId,
                ANSWER_HISTORY_LIMIT
        );
        // 获取会话摘要
        String conversationSummary = conversationSummaryService.getSummary(userId, conversationId);
        //意图识别
        IntentType intentType = identifyIntent(query, limitHistoryForIntent(historyMessages));
        QueryRewriteResult queryRewriteResult = rewriteQueryIfNeeded(query, intentType, conversationSummary, historyMessages);
        //构建单次聊天上下文对象
        AgentChatContext context = buildChatContext(
                userId,
                conversationId,
                query,
                queryRewriteResult.getRewrittenQuery(),
                intentType,
                historyMessages,
                conversationSummary
        );
        //回答前召回长期记忆（fail-open，写入 context.longTermMemories）
        longTermMemoryService.recall(context);
        String answer = route(intentType, context);
        //保存本次聊天
        shortTermMemoryService.saveUserAndAssistantMessages(
                userId,
                conversationId,
                query,
                answer,
                buildUserMessageMetadata(intentType, queryRewriteResult),
                Map.of("intentType", intentType.name())
        );
        conversationService.touchConversation(userId, conversationId, 2);
        //回答后触发长期记忆抽取（记住命令同步，常规异步，fail-open）
        longTermMemoryService.postTurn(userId, conversationId, query, answer, historyMessages, null);
        // 判断是否需要生成摘要
        conversationSummaryService.triggerSummaryIfNeeded(userId, conversationId);

        ChatResponse response = new ChatResponse();
        response.setConversationId(conversationId);
        response.setAnswer(answer);
        return response;
    }

    @Override
    public SseEmitter chatStream(ChatRequest request) {
        validateChatRequest(request);
        UserContext userContext = UserContextHolder.get();
        if (userContext == null || userContext.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }

        Span currentSpan = tracer.currentSpan();
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        SseEmitter emitter = new SseEmitter(120000L);
        CompletableFuture.runAsync(() -> {
            UserContextHolder.set(userContext);
            try {
                if (currentSpan != null) {
                    try (Tracer.SpanInScope ignored = tracer.withSpan(currentSpan)) {
                        if (mdcContext != null) {
                            MDC.setContextMap(mdcContext);
                        }
                        doChatStream(request, emitter);
                    }
                } else {
                    if (mdcContext != null) {
                        MDC.setContextMap(mdcContext);
                    }
                    doChatStream(request, emitter);
                }
                emitter.complete();
            } catch (Exception e) {
                sendEvent(emitter, "error", Map.of("message", e.getMessage() == null ? "Stream failed" : e.getMessage()));
                emitter.complete();
            } finally {
                UserContextHolder.clear();
                MDC.clear();
            }
        });
        return emitter;
    }

    /**
     * 兼容旧调用：直接通过 query 和 conversationId 发起聊天。
     */
    @Override
    public String chat(String query, String conversationId) {
        ChatRequest request = new ChatRequest();
        request.setQuery(query);
        request.setConversationId(conversationId);
        return chat(request).getAnswer();
    }

    /**
     * 根据意图类型路由到不同执行路径。
     */
    @Override
    public String route(IntentType intentType, AgentChatContext context) {
        StringBuilder answer = new StringBuilder();
        routeStream(intentType, context, answer::append);
        return answer.toString();
    }

    /**
     * 根据当前问题和最近会话历史识别意图。
     */
    private void doChatStream(ChatRequest request, SseEmitter emitter) {
        Long userId = requireCurrentUserId();
        String query = request.getQuery().trim();
        String conversationId = resolveConversationId(userId, request.getConversationId(), query);

        sendEvent(emitter, "meta", Map.of("conversationId", conversationId));

        List<AgentShortTermMemory> historyMessages = shortTermMemoryService.listRecentMessages(
                userId,
                conversationId,
                ANSWER_HISTORY_LIMIT
        );
        String conversationSummary = conversationSummaryService.getSummary(userId, conversationId);
        IntentType intentType = identifyIntent(query, limitHistoryForIntent(historyMessages));
        QueryRewriteResult queryRewriteResult = rewriteQueryIfNeeded(query, intentType, conversationSummary, historyMessages);

        sendEvent(emitter, "status", Map.of(
                "conversationId", conversationId,
                "intentType", intentType.name(),
                "rewrittenQuery", queryRewriteResult.getRewrittenQuery(),
                "queryRewritten", queryRewriteResult.isRewritten()
        ));

        AgentChatContext context = buildChatContext(
                userId,
                conversationId,
                query,
                queryRewriteResult.getRewrittenQuery(),
                intentType,
                historyMessages,
                conversationSummary
        );
        //回答前召回长期记忆（fail-open，写入 context.longTermMemories）
        longTermMemoryService.recall(context);

        StringBuilder answer = new StringBuilder();
        routeStream(intentType, context, delta -> {
            answer.append(delta);
            sendEvent(emitter, "delta", Map.of("content", delta));
        });

        String finalAnswer = answer.toString();
        shortTermMemoryService.saveUserAndAssistantMessages(
                userId,
                conversationId,
                query,
                finalAnswer,
                buildUserMessageMetadata(intentType, queryRewriteResult),
                Map.of("intentType", intentType.name())
        );
        conversationService.touchConversation(userId, conversationId, 2);
        //回答后触发长期记忆抽取（记住命令同步，常规异步，fail-open）
        longTermMemoryService.postTurn(userId, conversationId, query, finalAnswer, historyMessages, null);
        conversationSummaryService.triggerSummaryIfNeeded(userId, conversationId);

        sendEvent(emitter, "done", Map.of(
                "conversationId", conversationId,
                "answer", finalAnswer
        ));
    }

    private void routeStream(IntentType intentType, AgentChatContext context, Consumer<String> onDelta) {
        IntentType safeIntentType = intentType == null ? IntentType.SIMPLE_CHAT : intentType;

        switch (safeIntentType) {
            case KNOWLEDGE_BASE -> ragService.queryStream(
                    context.getQuery(),
                    context.getRewrittenQuery(),
                    context.getConversationSummary(),
                    context.getHistoryMessages(),
                    context.getLongTermMemories(),
                    onDelta
            );
            case COMPLEX_TASK -> {
                String routePrompt = PromptBuilder.buildRoutePrompt(
                        context.getQuery(),
                        context.getConversationSummary(),
                        context.getHistoryMessages()
                );
                // TODO 改为专门意图识别的大模型调用
                String routeResult = llmService.chat(routePrompt);
                String taskPrompt = null;
                try {
                    taskPrompt = PromptBuilder.buildTaskPrompt(context, routeResult);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                onDelta.accept(toolCallingService.chatWithTasks(context, taskPrompt));
            }
            case SIMPLE_CHAT -> {
                String prompt = PromptBuilder.buildSimplePrompt(
                        context.getQuery(),
                        context.getConversationSummary(),
                        context.getHistoryMessages(),
                        context.getLongTermMemories()
                );
                // TODO LLM调用超时采用下一个LLM
                llmService.chatStream(prompt, onDelta);
            }
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(SSE_OBJECT_MAPPER.writeValueAsString(data)));
        } catch (IOException e) {
            throw new RuntimeException("SSE send failed", e);
        }
    }

    @Override
    public IntentType identifyIntent(String query, List<AgentShortTermMemory> historyMessages) {
        if (query == null || query.trim().isEmpty()) {
            return IntentType.SIMPLE_CHAT;
        }

        IntentType ruleIntent = identifyByRules(query);
        if (ruleIntent != null) {
            log.info("identifyByRules ruleIntent = {}", ruleIntent);
            return ruleIntent;
        }

        try {
            String result = llmService.chat(buildIntentPrompt(query, historyMessages));
            log.info("LLM 意图识别结果： {}", result);
            return parseIntent(result);
        } catch (Exception e) {
            return IntentType.SIMPLE_CHAT;
        }
    }

    /**
     * 校验聊天请求的必要字段。
     */
    private void validateChatRequest(ChatRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户问题不能为空");
        }
    }

    /**
     * 获取当前登录用户 ID。
     */
    private Long requireCurrentUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return userId;
    }

    /**
     * 解析会话 ID。
     * <p>
     * 如果前端传入会话 ID，则校验归属；如果未传入，则自动创建一个新会话。
     */
    private String resolveConversationId(Long userId, String conversationId, String query) {
        if (conversationId != null && !conversationId.trim().isEmpty()) {
            String trimmedConversationId = conversationId.trim();
            conversationService.validateConversationOwner(userId, trimmedConversationId);
            return trimmedConversationId;
        }

        CreateConversationRequest createConversationRequest = new CreateConversationRequest();
        createConversationRequest.setTitle(buildConversationTitle(query));
        ConversationResponse conversation = conversationService.createConversation(createConversationRequest);
        return conversation.getConversationId();
    }

    /**
     * 根据首条用户问题生成默认会话标题。
     */
    private String buildConversationTitle(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String trimmed = query.trim();
        return trimmed.length() <= 20 ? trimmed : trimmed.substring(0, 20);
    }

    /**
     * 构建单次聊天上下文对象。
     */
    private AgentChatContext buildChatContext(
            Long userId,
            String conversationId,
            String query,
            String rewrittenQuery,
            IntentType intentType,
            List<AgentShortTermMemory> historyMessages,
            String conversationSummary
    ) {
        AgentChatContext context = new AgentChatContext();
        context.setUserId(userId);
        context.setConversationId(conversationId);
        context.setQuery(query);
        context.setRewrittenQuery(rewrittenQuery);
        context.setIntentType(intentType);
        context.setHistoryMessages(historyMessages);
        context.setConversationSummary(conversationSummary);
        return context;
    }

    /**
     * 截取意图识别阶段需要的少量历史消息。
     */
    private QueryRewriteResult rewriteQueryIfNeeded(
            String query,
            IntentType intentType,
            String conversationSummary,
            List<AgentShortTermMemory> historyMessages
    ) {
        if (intentType != IntentType.KNOWLEDGE_BASE) {
            return QueryRewriteResult.unchanged(query);
        }
        return queryRewriteService.rewrite(query, conversationSummary, historyMessages);
    }

    private Map<String, Object> buildUserMessageMetadata(IntentType intentType, QueryRewriteResult queryRewriteResult) {
        if (queryRewriteResult == null) {
            return Map.of("intentType", intentType.name());
        }
        return Map.of(
                "intentType", intentType.name(),
                "rewrittenQuery", queryRewriteResult.getRewrittenQuery(),
                "queryRewritten", queryRewriteResult.isRewritten()
        );
    }

    private List<AgentShortTermMemory> limitHistoryForIntent(List<AgentShortTermMemory> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return List.of();
        }
        int fromIndex = Math.max(0, historyMessages.size() - INTENT_HISTORY_LIMIT);
        return historyMessages.subList(fromIndex, historyMessages.size());
    }

    /**
     * 使用规则关键词快速识别意图。
     */
    private IntentType identifyByRules(String query) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);

        String[] explicitKnowledgeKeywords = {
                "知识库", "公司知识库", "内部文档", "内部资料", "内部制度", "内部规定", "内部规则",
                "员工手册", "公司手册", "规章制度", "人员信息", "组织架构", "报销制度", "考勤制度",
                "请假制度", "年假规定", "薪酬制度", "福利制度", "入职流程", "离职流程", "审批流程",
                "检索知识库"
        };
        for (String keyword : explicitKnowledgeKeywords) {
            if (normalized.contains(keyword)) {
                return IntentType.KNOWLEDGE_BASE;
            }
        }

        String[] internalScopeKeywords = {
                "公司", "内部", "本公司", "我们公司", "我司", "单位", "部门", "员工", "人员",
                "同事", "组织", "人事", "hr", "行政", "财务", "报销", "考勤", "请假",
                "年假", "入职", "离职", "审批", "合同", "薪酬", "福利"
        };
        String[] knowledgeTopicKeywords = {
                "制度", "规定", "规则", "手册", "文档", "文件", "资料", "信息", "流程",
                "政策", "规范", "联系人", "负责人", "架构"
        };
        if (containsAny(normalized, internalScopeKeywords) && containsAny(normalized, knowledgeTopicKeywords)) {
            return IntentType.KNOWLEDGE_BASE;
        }

        return null;
    }

    private boolean containsAny(String normalized, String[] keywords) {
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构建意图识别 Prompt。
     */
    private String buildIntentPrompt(String query, List<AgentShortTermMemory> historyMessages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个意图识别器。请判断用户问题属于以下哪一种意图：\n");
        prompt.append("1. KNOWLEDGE_BASE：只有当用户问题明确指向公司内部制度、内部流程、内部资料、员工/人员/组织信息、行政人事财务等公司内部信息时才选择。\n");
        prompt.append("2. SIMPLE_CHAT：普通聊天、解释概念、闲聊，或不需要知识库和工具的问题。\n\n");
        prompt.append("3. COMPLEX_TASK：对于需要调用工具来解决的复杂问题。\n");
        prompt.append("### 判断边界\n");
        prompt.append("- 不要因为问题里出现“文档、资料、制度、规则、规定、文件、手册”等词就直接选择 KNOWLEDGE_BASE。\n");
        prompt.append("- 如果用户问的是通用知识、公共规则、编程文件、学习资料、概念解释、写作建议或普通闲聊，应选择 SIMPLE_CHAT。\n");
        prompt.append("- 只有问题语义明确落在公司内部范围，或结合最近会话可确认是在追问公司内部信息时，才选择 KNOWLEDGE_BASE。\n");
        prompt.append("- 如果用户只是要求执行动作、查询外部实时信息、执行复杂任务或使用工具，应选择 COMPLEX_TASK。\n\n");

        prompt.append("### 最近会话\n");
        if (historyMessages == null || historyMessages.isEmpty()) {
            prompt.append("暂无历史会话。\n\n");
        } else {
            for (AgentShortTermMemory message : historyMessages) {
                if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                    continue;
                }
                prompt.append(message.getRole())
                        .append(": ")
                        .append(message.getContent().trim())
                        .append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("只返回一个枚举值，不要输出任何解释。\n\n");
        prompt.append("### 当前用户问题\n");
        prompt.append(query).append("\n");
        return prompt.toString();
    }

    /**
     * 解析大模型返回的意图枚举。
     */
    private IntentType parseIntent(String modelResult) {
        if (modelResult == null || modelResult.trim().isEmpty()) {
            return IntentType.SIMPLE_CHAT;
        }

        String normalized = modelResult.trim().toUpperCase(Locale.ROOT);
        for (IntentType intentType : IntentType.values()) {
            if (normalized.contains(intentType.name())) {
                return intentType;
            }
        }

        return IntentType.SIMPLE_CHAT;
    }
}
