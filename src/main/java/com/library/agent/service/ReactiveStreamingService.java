package com.library.agent.service;

import com.library.agent.auth.context.UserContext;
import com.library.agent.auth.context.UserContextHolder;
import com.library.agent.context.AgentChatContext;
import com.library.agent.conversation.service.ConversationService;
import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.enums.IntentType;
import com.library.agent.llm.LlmExhaustedException;
import com.library.agent.llm.LlmService;
import com.library.agent.llm.PromptBuilder;
import com.library.agent.llm.QueryRewriteResult;
import com.library.agent.llm.QueryRewriteService;
import com.library.agent.llm.TaskRoutingService;
import com.library.agent.llm.ToolCallingService;
import com.library.agent.memory.ConversationSummaryService;
import com.library.agent.memory.LongTermMemoryService;
import com.library.agent.memory.ShortTermMemoryService;
import com.library.agent.observability.ConversationTraceCollector;
import com.library.agent.observability.ConversationTraceService;
import com.library.agent.rag.service.RagService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 响应式流式输出服务。
 * <p>
 * 通过 SseEmitter 将 LLM 的 token 流实时推送到 WebFlux/MVC 前端。
 * 仅流式输出 LLM 最终文本，不包含中间工具调用和思考过程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReactiveStreamingService {

    private static final int ANSWER_HISTORY_LIMIT = 20;
    private static final int INTENT_HISTORY_LIMIT = 5;
    /* 非 LLM 类的内部故障（检索、记忆、持久化等）对外的统一文案，技术细节只进日志与 trace */
    private static final String GENERIC_ERROR_MESSAGE = "服务暂时不可用，请稍后再试。";

    private final LlmService llmService;
    private final RagService ragService;
    private final ConversationService conversationService;
    private final ShortTermMemoryService shortTermMemoryService;
    private final ConversationSummaryService conversationSummaryService;
    private final ToolCallingService toolCallingService;
    private final TaskRoutingService taskRoutingService;
    private final QueryRewriteService queryRewriteService;
    private final LongTermMemoryService longTermMemoryService;
    private final ConversationTraceService traceService;
    private final Tracer tracer;

    /**
     * 流式对话入口。
     * <p>
     * 在后台线程执行意图识别、路由和 LLM 流式调用，
     * 通过 SseEmitter 将每个 token 以 SSE delta 事件推送给前端。
     */
    public SseEmitter chatReactive(Long userId, String conversationId, String query) {
        SseEmitter emitter = new SseEmitter(120000L);

        Span currentSpan = tracer.currentSpan();
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        UserContext userContext = UserContextHolder.get();

        CompletableFuture.runAsync(() -> {
            UserContextHolder.set(userContext);
            try {
                if (currentSpan != null) {
                    try (Tracer.SpanInScope ignored = tracer.withSpan(currentSpan)) {
                        if (mdcContext != null) MDC.setContextMap(mdcContext);
                        doChatAndStream(userId, conversationId, query, emitter);
                        emitter.complete();
                    }
                } else {
                    doChatAndStream(userId, conversationId, query, emitter);
                    emitter.complete();
                }
            } catch (Exception e) {
                log.error("Reactive chat failed, userId={}, convId={}", userId, conversationId, e);
                sendEvent(emitter, "error", Map.of("message", userFacingMessage(e)));
                try {
                    ConversationTraceCollector errCollector = new ConversationTraceCollector(userId, conversationId, query);
                    traceService.save(errCollector, "ERROR", e.getMessage());
                } catch (Exception ignored) { }
                emitter.complete();
            } finally {
                UserContextHolder.clear();
                MDC.clear();
            }
        });

        return emitter;
    }

    /**
     * 执行用户query请求
     * @param userId 用户id
     * @param conversationId 会话id
     * @param query 用户输入的query
     * @param emitter
     */
    private void doChatAndStream(Long userId, String conversationId, String query, SseEmitter emitter) {
        /* 0. 创建可观测采集器 */
        ConversationTraceCollector collector = new ConversationTraceCollector(userId, conversationId, query);

        /* 1. 加载历史 + 摘要 */
        List<AgentShortTermMemory> historyMessages =
                shortTermMemoryService.listRecentMessages(userId, conversationId, ANSWER_HISTORY_LIMIT);
        String summary = conversationSummaryService.getSummary(userId, conversationId);

        /* 2. 意图识别 */
        IntentType intentType = identifyIntent(query, limitHistoryForIntent(historyMessages), collector);
        collector.setIntentType(intentType.name());

        /* 3. 查询改写 */
        QueryRewriteResult rewriteResult =
                rewriteQueryIfNeeded(query, intentType, summary, historyMessages);

        /* 4. 发送 meta + status 事件；meta 携带 traceId 供评测侧关联可观测数据 */
        sendEvent(emitter, "meta", Map.of(
                "conversationId", conversationId,
                "traceId", collector.getTraceId()
        ));
        sendEvent(emitter, "status", Map.of(
                "conversationId", conversationId,
                "intentType", intentType.name(),
                "rewrittenQuery", rewriteResult.getRewrittenQuery(),
                "queryRewritten", rewriteResult.isRewritten()
        ));

        /* 5. 构建上下文 + 回答前召回长期记忆（fail-open） */
        AgentChatContext context = buildChatContext(
                userId, conversationId, query, rewriteResult.getRewrittenQuery(),
                intentType, historyMessages, summary);
        longTermMemoryService.recall(context);

        /* 6. 流式路由 */
        StringBuilder fullAnswer = new StringBuilder();
        routeStream(intentType, context, collector, token -> {
            fullAnswer.append(token);
            sendEvent(emitter, "delta", Map.of("content", token));
        });

        /* 7. 保存消息 + 刷新会话活跃度 + 回答后触发长期记忆抽取（记住命令同步，常规异步，fail-open） */
        shortTermMemoryService.saveUserAndAssistantMessages(
                userId, conversationId, query, fullAnswer.toString(),
                Map.of("intentType", intentType.name()),
                Map.of("intentType", intentType.name()));
        conversationService.touchConversation(userId, conversationId, 2);
        longTermMemoryService.postTurn(userId, conversationId, query, fullAnswer.toString(), historyMessages, null);
        conversationSummaryService.triggerSummaryIfNeeded(userId, conversationId);

        /* 8. 保存可观测数据 */
        traceService.save(collector, "SUCCESS", null);

        /* 9. done 事件 */
        sendEvent(emitter, "done", Map.of(
                "conversationId", conversationId,
                "answer", fullAnswer.toString()
        ));
    }

    /**
     * 按意图类型路由到流式或非流式执行路径。
     * <p>
     * KNOWLEDGE_BASE 走 RAG + LLM token-level 流式输出；
     * COMPLEX_TASK 走 ReAct 阻塞调用，拿到完整答案后分块以打字机效果输出。
     */
    private void routeStream(IntentType intentType, AgentChatContext context,
                             ConversationTraceCollector collector,
                             java.util.function.Consumer<String> onDelta) {
        IntentType safeType = intentType == null ? IntentType.COMPLEX_TASK : intentType;

        switch (safeType) {
            case KNOWLEDGE_BASE -> {
                String prompt = ragService.queryStream(
                        context.getQuery(),
                        context.getRewrittenQuery(),
                        context.getConversationSummary(),
                        context.getHistoryMessages(),
                        context.getLongTermMemories(),
                        onDelta
                );
                recordLlmStreamCall(collector, "RAG_GENERATE", prompt);
            }
            case COMPLEX_TASK -> {
                TaskRoutingService.RouteResolution resolution = taskRoutingService.resolve(context, collector);
                if (!resolution.matched()) {
                    streamChunks(resolution.noMatchMessage(), onDelta);
                    return;
                }
                String taskPrompt;
                try {
                    taskPrompt = PromptBuilder.buildTaskPrompt(context, resolution.task().routeName());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                String answer = toolCallingService.chatWithTasks(context, taskPrompt, collector);
                /* 分块输出，模拟打字机效果 */
                streamChunks(answer, onDelta);
            }
        }
    }

    /**
     * 记录一次流式 LLM 调用到采集器。
     * <p>
     * 在 chatStream 返回后调用，从 ThreadLocal 读取 token 用量。
     */
    private void recordLlmStreamCall(ConversationTraceCollector collector,
                                     String callType, String prompt) {
        LlmService.TokenUsage usage = llmService.getLastTokenUsage();
        collector.recordLlmCall(llmService.getLastUsedModel(), callType, prompt, "(streaming)",
                usage != null ? usage.getInputTokens() : 0,
                usage != null ? usage.getOutputTokens() : 0,
                0);
        llmService.clearLastTokenUsage();
    }

    /**
     * 将文本按固定大小分块，逐块推送给前端以实现打字机效果。
     * <p>
     * 用于 COMPLEX_TASK 等非流式路径：ReAct 返回完整答案后，
     * 通过分块发送 delta 事件让前端逐段渲染。
     */
    private void streamChunks(String text, java.util.function.Consumer<String> onDelta) {
        if (text == null || text.isEmpty()) return;
        final int chunkSize = 6; /* 约 3 个中文字或 6 个英文字符 */
        for (int i = 0; i < text.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, text.length());
            onDelta.accept(text.substring(i, end));
            try {
                Thread.sleep(30); /* 打字机间隔，防止 TCP 合并多个 delta */
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /* ==================== 意图识别 ==================== */

    private IntentType identifyIntent(String query, List<AgentShortTermMemory> history,
                                       ConversationTraceCollector collector) {
        if (query == null || query.trim().isEmpty()) {
            return IntentType.COMPLEX_TASK;
        }
        /* 用户显式指令关键词优先 */
        IntentType keywordIntent = IntentType.matchByKeyword(query);
        if (keywordIntent != null) {
            log.info("Reactive intent keyword hit: {} -> {}", query, keywordIntent);
            return keywordIntent;
        }
        /* LLM 识别 */
        try {
            String intentPrompt = PromptBuilder.buildIntentPrompt(query, history);
            long startMs = System.currentTimeMillis();
            String result = llmService.chat(intentPrompt);
            long duration = System.currentTimeMillis() - startMs;

            LlmService.TokenUsage usage = llmService.getLastTokenUsage();
            collector.recordLlmCall(llmService.getLastUsedModel(), "INTENT", intentPrompt, result,
                    usage != null ? usage.getInputTokens() : 0,
                    usage != null ? usage.getOutputTokens() : 0,
                    duration);
            llmService.clearLastTokenUsage();

            log.info("Reactive LLM intent result: {}", result);
            for (IntentType t : IntentType.values()) {
                if (result != null && result.toUpperCase(Locale.ROOT).contains(t.name())) return t;
            }
        } catch (Exception e) {
            log.warn("Reactive intent LLM call failed, fallback to COMPLEX_TASK", e);
        }
        return IntentType.COMPLEX_TASK;
    }

    /* ==================== 辅助方法 ==================== */

    private QueryRewriteResult rewriteQueryIfNeeded(String query, IntentType intentType,
                                                     String summary, List<AgentShortTermMemory> history) {
        if (intentType != IntentType.KNOWLEDGE_BASE) return QueryRewriteResult.unchanged(query);
        return queryRewriteService.rewrite(query, summary, history);
    }

    private AgentChatContext buildChatContext(Long userId, String convId, String query,
                                               String rewritten, IntentType intent,
                                               List<AgentShortTermMemory> history, String summary) {
        AgentChatContext ctx = new AgentChatContext();
        ctx.setUserId(userId);
        ctx.setConversationId(convId);
        ctx.setQuery(query);
        ctx.setRewrittenQuery(rewritten);
        ctx.setIntentType(intent);
        ctx.setHistoryMessages(history);
        ctx.setConversationSummary(summary);
        return ctx;
    }

    private List<AgentShortTermMemory> limitHistoryForIntent(List<AgentShortTermMemory> history) {
        if (history == null || history.isEmpty()) return List.of();
        int from = Math.max(0, history.size() - INTENT_HISTORY_LIMIT);
        return history.subList(from, history.size());
    }

    /**
     * 取对外的 error 事件文案。
     * <p>
     * LLM 链路耗尽时沿用编排器的统一友好文案；其余内部故障一律给通用文案。
     * 原始异常文本只写日志与 trace 表，避免把鉴权、参数、SQL 等内部细节透传给前端。
     */
    private static String userFacingMessage(Exception e) {
        return e instanceof LlmExhaustedException ? e.getMessage() : GENERIC_ERROR_MESSAGE;
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            /* 连接已关闭，停止发送 */
        }
    }
}
