package com.library.agent.llm.resilience;

import com.library.agent.llm.LlmExhaustedException;
import com.library.agent.llm.resilience.ChatModelFailoverOrchestrator.NamedChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 链路二容错编排器单测（mock ChatModel，不联网）。
 * <p>
 * 覆盖与链路一对齐的口径：429/5xx 同模型重试后降级、4xx 不重试也不降级（立即终止）、
 * 网络故障按可重试处理、全部耗尽抛统一友好文案、连续失败触发熔断后跳过该模型。
 */
class ChatModelFailoverOrchestratorTest {

    private static final String SCENE = "react";
    private static final int NO_RETRY = 0;
    private static final long NO_BACKOFF = 0;
    /* 高阈值确保测试过程中熔断不触发，专注顺序与降级 */
    private static final int BREAKER_OFF = Integer.MAX_VALUE;

    private static ChatRequest request() {
        return ChatRequest.builder().messages(UserMessage.from("ping")).build();
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static NamedChatModel model(String label, ChatModel chatModel) {
        return new NamedChatModel(label, chatModel);
    }

    private static ChatModelFailoverOrchestrator orchestrator(List<NamedChatModel> models, int retryAttempts) {
        return new ChatModelFailoverOrchestrator(models, SCENE, retryAttempts, NO_BACKOFF,
                BREAKER_OFF, 10_000);
    }

    @Test
    void rateLimitRetriesThenFallsBackToDegradeModel() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel degrade = mock(ChatModel.class);

        /* 429：先同模型重试一次，仍失败才降级 */
        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new RateLimitException("too many requests"))
                .thenThrow(new RateLimitException("too many requests"));
        when(degrade.chat(any(ChatRequest.class))).thenReturn(response("answer-from-deepseek"));

        ChatResponse result = orchestrator(
                List.of(model("bailian/qwen-plus", primary), model("deepseek/chat", degrade)), 1)
                .chat(request());

        assertEquals("answer-from-deepseek", result.aiMessage().text());
        verify(primary, times(2)).chat(any(ChatRequest.class));
        verify(degrade, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void serverErrorRetriesThenFallsBackToDegradeModel() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel degrade = mock(ChatModel.class);

        /* 5xx：与 429 同样按可重试处理，先同模型重试 */
        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new InternalServerException("gateway unavailable"));
        when(degrade.chat(any(ChatRequest.class))).thenReturn(response("answer-from-deepseek"));

        ChatResponse result = orchestrator(
                List.of(model("bailian/qwen-plus", primary), model("deepseek/chat", degrade)), 1)
                .chat(request());

        assertEquals("answer-from-deepseek", result.aiMessage().text());
        verify(primary, times(2)).chat(any(ChatRequest.class));
        verify(degrade, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void unrecognizedExceptionAbortsWithoutRetryOrFailover() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel degrade = mock(ChatModel.class);

        /* 既非 RetriableException 也非 IO 包装的意外异常：无从判断可恢复性，立即终止 */
        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new LangChain4jException("unexpected internal failure"));
        when(degrade.chat(any(ChatRequest.class))).thenReturn(response("answer-from-deepseek"));

        ChatModelFailoverOrchestrator orchestrator = orchestrator(
                List.of(model("bailian/qwen-plus", primary), model("deepseek/chat", degrade)), 2);

        assertThrows(LlmExhaustedException.class, () -> orchestrator.chat(request()));
        verify(primary, times(1)).chat(any(ChatRequest.class));
        verify(degrade, never()).chat(any(ChatRequest.class));
    }

    @Test
    void authErrorAbortsWithoutRetryOrFailover() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel degrade = mock(ChatModel.class);

        /* 401 属确定性错误：同模型内不重试，也不降级（换 key 也修不好同一份无效凭证） */
        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new AuthenticationException("invalid api key"));
        when(degrade.chat(any(ChatRequest.class))).thenReturn(response("answer-from-deepseek"));

        ChatModelFailoverOrchestrator orchestrator = orchestrator(
                List.of(model("bailian/qwen-plus", primary), model("deepseek/chat", degrade)), 2);

        LlmExhaustedException ex = assertThrows(LlmExhaustedException.class,
                () -> orchestrator.chat(request()));
        assertInstanceOf(AuthenticationException.class, ex.getCause());
        verify(primary, times(1)).chat(any(ChatRequest.class));
        verify(degrade, never()).chat(any(ChatRequest.class));
    }

    @Test
    void invalidRequestAbortsWithoutRetryOrFailover() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel degrade = mock(ChatModel.class);

        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new InvalidRequestException("model not found"));
        when(degrade.chat(any(ChatRequest.class))).thenReturn(response("answer-from-deepseek"));

        ChatModelFailoverOrchestrator orchestrator = orchestrator(
                List.of(model("bailian/qwen-plus", primary), model("deepseek/chat", degrade)), 2);

        assertThrows(LlmExhaustedException.class, () -> orchestrator.chat(request()));
        verify(primary, times(1)).chat(any(ChatRequest.class));
        verify(degrade, never()).chat(any(ChatRequest.class));
    }

    @Test
    void networkFailureIsRetriedWithinModel() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel degrade = mock(ChatModel.class);

        /* 连接/超时类故障无 HTTP 状态码，被包装为 LangChain4jException(cause=IOException)，按可重试处理 */
        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new LangChain4jException(new IOException("connect timed out")))
                .thenReturn(response("recovered-on-primary"));

        ChatResponse result = orchestrator(
                List.of(model("bailian/qwen-plus", primary), model("deepseek/chat", degrade)), 1)
                .chat(request());

        assertEquals("recovered-on-primary", result.aiMessage().text());
        verify(primary, times(2)).chat(any(ChatRequest.class));
        verify(degrade, never()).chat(any(ChatRequest.class));
    }

    @Test
    void throwsFriendlyMessageWhenAllModelsExhausted() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel degrade = mock(ChatModel.class);

        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new RateLimitException("primary limited"));
        when(degrade.chat(any(ChatRequest.class)))
                .thenThrow(new RateLimitException("degrade limited"));

        ChatModelFailoverOrchestrator orchestrator = orchestrator(
                List.of(model("bailian/qwen-plus", primary), model("deepseek/chat", degrade)), NO_RETRY);

        LlmExhaustedException ex = assertThrows(LlmExhaustedException.class,
                () -> orchestrator.chat(request()));
        assertTrue(ex.getMessage().contains("暂时不可用"));
        assertInstanceOf(RateLimitException.class, ex.getCause());
    }

    @Test
    void circuitOpensAfterConsecutiveRetryableFailuresAndSkipsModel() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel degrade = mock(ChatModel.class);

        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new RateLimitException("primary limited"));
        when(degrade.chat(any(ChatRequest.class))).thenReturn(response("answer-from-deepseek"));

        /* 阈值 2：连续两次可重试失败即熔断主模型 */
        ChatModelFailoverOrchestrator orchestrator = new ChatModelFailoverOrchestrator(
                List.of(model("bailian/qwen-plus", primary), model("deepseek/chat", degrade)), SCENE,
                NO_RETRY, NO_BACKOFF, 2, 60_000);

        orchestrator.chat(request());
        orchestrator.chat(request());
        orchestrator.chat(request());

        /* 前两次打主模型并降级，第三次起主模型已熔断被跳过 */
        verify(primary, times(2)).chat(any(ChatRequest.class));
        verify(degrade, times(3)).chat(any(ChatRequest.class));
    }

    @Test
    void deterministicFailureDoesNotTripCircuitBreaker() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel degrade = mock(ChatModel.class);

        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new AuthenticationException("invalid api key"));
        when(degrade.chat(any(ChatRequest.class))).thenReturn(response("answer-from-deepseek"));

        ChatModelFailoverOrchestrator orchestrator = new ChatModelFailoverOrchestrator(
                List.of(model("bailian/qwen-plus", primary), model("deepseek/chat", degrade)), SCENE,
                NO_RETRY, NO_BACKOFF, 1, 60_000);

        /* 确定性错误不计熔断：即使阈值仅 1，主模型也应每次都被尝试（而非被跳过） */
        for (int i = 0; i < 3; i++) {
            assertThrows(LlmExhaustedException.class, () -> orchestrator.chat(request()));
        }

        verify(primary, times(3)).chat(any(ChatRequest.class));
        verify(degrade, never()).chat(any(ChatRequest.class));
    }
}
