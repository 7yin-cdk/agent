package com.library.agent.llm;

import com.library.agent.context.AgentChatContext;
import com.library.agent.enums.AgentTask;
import com.library.agent.llm.impl.TaskRoutingServiceImpl;
import com.library.agent.observability.ConversationTraceCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TaskRoutingServiceImpl 路由决议单测（离线，stub LlmService 脚本化返回）。
 * <p>
 * 覆盖：非法任务名 → 纠错重试成功；连续非法 → 无匹配能力回退；
 * 并锁定每次路由 LLM 调用都写入可观测采集器（防遥测丢失）。
 */
class TaskRoutingServiceTest {

    private ScriptedLlmService llmService;
    private TaskRoutingServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        llmService = new ScriptedLlmService();
        service = new TaskRoutingServiceImpl(llmService);
        Field attempts = TaskRoutingServiceImpl.class.getDeclaredField("maxRouteAttempts");
        attempts.setAccessible(true);
        attempts.set(service, 2);
    }

    @Test
    void firstInvalidThenRetrySucceeds() {
        llmService.push(
                "{\"task\":\"code_generation\"}",
                "{\"task\":\"slow_query\"}"
        );
        CountingCollector collector = new CountingCollector(1L, "conv-1", "帮我查慢查询");

        TaskRoutingService.RouteResolution resolution = service.resolve(context("帮我查一下慢查询"), collector);

        assertTrue(resolution.matched(), "纠错重试后应命中任务");
        assertEquals("slow_query", resolution.task().routeName());
        assertEquals(2, resolution.attempts().size(), "应产生两次路由调用");

        String retryPrompt = resolution.attempts().get(1).prompt();
        assertTrue(retryPrompt.contains("上一轮路由无效"), "纠错轮提示应说明上一轮无效");
        assertTrue(retryPrompt.contains("code_generation"), "纠错轮提示应指出上一轮的非法任务名");
        assertTrue(retryPrompt.contains(AgentTask.routeNamesText()), "纠错轮提示应硬性列出合法任务名");
        assertTrue(retryPrompt.contains("weather_query") && retryPrompt.contains("slow_query"),
                "合法任务名清单应包含全部任务");
        assertEquals(2, collector.count(), "每次路由 LLM 调用都应写入遥测");
    }

    @Test
    void consecutiveInvalidFallsBackToNoMatchMessage() {
        llmService.push(
                "{\"task\":\"code_generation\"}",
                "{\"task\":\"no_such_task_xyz\"}"
        );

        TaskRoutingService.RouteResolution resolution = service.resolve(context("帮我巡检一下数据库健康状况"));

        assertFalse(resolution.matched(), "连续非法应判定为无匹配");
        assertEquals(2, resolution.attempts().size());
        String message = resolution.noMatchMessage();
        assertTrue(message.contains("未能从现有能力中匹配到可执行模块"), "回退文案应明确告知无匹配能力");
        for (AgentTask task : AgentTask.values()) {
            assertTrue(message.contains(task.routeName()), "回退文案应列出可用能力: " + task.routeName());
        }
    }

    @Test
    void firstAttemptValidShortCircuits() {
        llmService.push("{\"task\":\"database_metrics\"}");
        CountingCollector collector = new CountingCollector(2L, "conv-2", "采集指标");

        TaskRoutingService.RouteResolution resolution = service.resolve(context("采集数据库指标"), collector);

        assertTrue(resolution.matched());
        assertEquals("database_metrics", resolution.task().routeName());
        assertEquals(1, resolution.attempts().size(), "首轮命中不应再重试");
        assertEquals(1, collector.count());
    }

    private AgentChatContext context(String query) {
        AgentChatContext ctx = new AgentChatContext();
        ctx.setUserId(1L);
        ctx.setConversationId("conv-1");
        ctx.setQuery(query);
        ctx.setConversationSummary("");
        ctx.setHistoryMessages(List.of());
        return ctx;
    }

    /**
     * 可脚本化返回序列的 LlmService 桩；未用到的方法一律抛 UnsupportedOperationException。
     */
    private static final class ScriptedLlmService implements LlmService {

        private final java.util.Deque<String> responses = new java.util.ArrayDeque<>();
        private final TokenUsage usage = new TokenUsage(10, 5, 15);

        void push(String... outputs) {
            for (String output : outputs) {
                responses.addLast(output);
            }
        }

        @Override
        public String chat(String prompt) {
            return responses.isEmpty() ? "{}" : responses.pollFirst();
        }

        @Override
        public void chatStream(String prompt, Consumer<String> onDelta) {
            throw new UnsupportedOperationException("chatStream not used in routing");
        }

        @Override
        public List<List<Float>> embed(List<String> texts) {
            throw new UnsupportedOperationException("embed not used in routing");
        }

        @Override
        public List<Float> embed(String text) {
            throw new UnsupportedOperationException("embed not used in routing");
        }

        @Override
        public List<Integer> rerank(String query, List<String> documents, int topN, double minScore) {
            throw new UnsupportedOperationException("rerank not used in routing");
        }

        @Override
        public TokenUsage getLastTokenUsage() {
            return usage;
        }

        @Override
        public void clearLastTokenUsage() {
            /* 无状态桩，无需清理 */
        }
    }

    /**
     * 记录 recordLlmCall 调用次数的采集器。
     */
    private static final class CountingCollector extends ConversationTraceCollector {

        private int count;

        CountingCollector(Long userId, String conversationId, String userQuery) {
            super(userId, conversationId, userQuery);
        }

        int count() {
            return count;
        }

        @Override
        public void recordLlmCall(String modelName, String callType,
                                  String inputPrompt, String outputResponse,
                                  int inputTokens, int outputTokens, long durationMs) {
            count++;
            super.recordLlmCall(modelName, callType, inputPrompt, outputResponse,
                    inputTokens, outputTokens, durationMs);
        }
    }
}
