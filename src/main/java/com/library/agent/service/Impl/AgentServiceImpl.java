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
import com.library.agent.llm.Assistant;
import com.library.agent.llm.LlmService;
import com.library.agent.llm.PromptBuilder;
import com.library.agent.memory.ConversationSummaryService;
import com.library.agent.memory.ShortTermMemoryService;
import com.library.agent.rag.service.RagService;
import com.library.agent.service.AgentService;
import lombok.RequiredArgsConstructor;
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
    private final Assistant  assistant;

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
        String conversationSummary = conversationSummaryService.getSummary(userId, conversationId);
        //意图识别
        IntentType intentType = identifyIntent(query, limitHistoryForIntent(historyMessages));
        //构建单次聊天上下文对象
        AgentChatContext context = buildChatContext(
                userId,
                conversationId,
                query,
                intentType,
                historyMessages,
                conversationSummary
        );
        String answer = route(intentType, context);
        //保存本次聊天
        shortTermMemoryService.saveUserAndAssistantMessages(userId, conversationId, query, answer);
        conversationService.touchConversation(userId, conversationId, 2);
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

        SseEmitter emitter = new SseEmitter(120000L);
        CompletableFuture.runAsync(() -> {
            UserContextHolder.set(userContext);
            try {
                doChatStream(request, emitter);
                emitter.complete();
            } catch (Exception e) {
                sendEvent(emitter, "error", Map.of("message", e.getMessage() == null ? "Stream failed" : e.getMessage()));
                emitter.complete();
            } finally {
                UserContextHolder.clear();
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

        sendEvent(emitter, "status", Map.of(
                "conversationId", conversationId,
                "intentType", intentType.name()
        ));

        AgentChatContext context = buildChatContext(
                userId,
                conversationId,
                query,
                intentType,
                historyMessages,
                conversationSummary
        );

        StringBuilder answer = new StringBuilder();
        routeStream(intentType, context, delta -> {
            answer.append(delta);
            sendEvent(emitter, "delta", Map.of("content", delta));
        });

        String finalAnswer = answer.toString();
        shortTermMemoryService.saveUserAndAssistantMessages(userId, conversationId, query, finalAnswer);
        conversationService.touchConversation(userId, conversationId, 2);
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
                    context.getConversationSummary(),
                    context.getHistoryMessages(),
                    onDelta
            );
            case TOOL_CALL -> {
                String toolPrompt = PromptBuilder.buildToolPrompt(
                        context.getQuery(),
                        context.getConversationSummary(),
                        context.getHistoryMessages()
                );
                onDelta.accept(assistant.chat(toolPrompt));
            }
            case SIMPLE_CHAT -> {
                String prompt = PromptBuilder.buildSimplePrompt(
                        context.getQuery(),
                        context.getConversationSummary(),
                        context.getHistoryMessages()
                );
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
            return ruleIntent;
        }

        try {
            String result = llmService.chat(buildIntentPrompt(query, historyMessages));
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
            IntentType intentType,
            List<AgentShortTermMemory> historyMessages,
            String conversationSummary
    ) {
        AgentChatContext context = new AgentChatContext();
        context.setUserId(userId);
        context.setConversationId(conversationId);
        context.setQuery(query);
        context.setIntentType(intentType);
        context.setHistoryMessages(historyMessages);
        context.setConversationSummary(conversationSummary);
        return context;
    }

    /**
     * 截取意图识别阶段需要的少量历史消息。
     */
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

        String[] toolKeywords = {
                "调用工具", "使用工具", "执行", "运行", "生成报告", "导出", "查询天气",
                "天气", "计算", "帮我查", "帮我生成", "tool", "api"
        };
        for (String keyword : toolKeywords) {
            if (normalized.contains(keyword)) {
                return IntentType.TOOL_CALL;
            }
        }

        String[] knowledgeKeywords = {
                "知识库", "文档", "资料", "制度", "规则", "规定", "手册", "文件",
                "根据", "参考", "rag", "检索"
        };
        for (String keyword : knowledgeKeywords) {
            if (normalized.contains(keyword)) {
                return IntentType.KNOWLEDGE_BASE;
            }
        }

        return null;
    }

    /**
     * 构建意图识别 Prompt。
     */
    private String buildIntentPrompt(String query, List<AgentShortTermMemory> historyMessages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个意图识别器。请判断用户问题属于以下哪一种意图：\n");
        prompt.append("1. KNOWLEDGE_BASE：需要查询知识库、文档、资料、制度、规则后回答。只有用户问到文档，文件相关的问题才需要查询知识库git\n");
        prompt.append("2. TOOL_CALL：需要调用外部工具、接口、数据库、计算器或执行动作。\n");
        prompt.append("3. SIMPLE_CHAT：普通聊天、解释概念、闲聊，或不需要知识库和工具的问题。\n\n");

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
