package com.library.agent.llm.resilience;

import com.library.agent.llm.LlmExhaustedException;
import com.library.agent.llm.client.ChatProvider;
import com.library.agent.llm.client.ChatResult;
import com.library.agent.llm.client.LlmCallException;
import com.library.agent.llm.client.OpenAiChatClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排器三级容错单测（mock 客户端，不联网）：
 * 降级顺序、provider 内重试、确定性错误不重试、耗尽兜底文案、流式首 token 语义。
 */
class ChatFailoverOrchestratorTest {

    private static final String SYSTEM = "SYSTEM";
    private static final String USER = "USER";
    private static final int NO_RETRY = 0;
    private static final long NO_BACKOFF = 0;

    private static ChatProvider provider(String name) {
        return ChatProvider.of(name, "https://example.com/" + name, "key-" + name, "model-" + name);
    }

    /* 高阈值确保测试过程中熔断不触发，专注顺序与降级 */
    private static ChatFailoverOrchestrator orchestrator(OpenAiChatClient client,
                                                         List<ChatProvider> providers,
                                                         int retryAttempts) {
        return new ChatFailoverOrchestrator(client, providers, retryAttempts, NO_BACKOFF,
                Integer.MAX_VALUE, 10_000);
    }

    @Test
    void fallsBackToSecondaryWhenPrimaryFails() {
        ChatProvider deepseek = provider("deepseek");
        ChatProvider bailian = provider("bailian");
        OpenAiChatClient client = mock(OpenAiChatClient.class);

        when(client.chat(eq(deepseek), eq(SYSTEM), eq(USER)))
                .thenThrow(new LlmCallException("deepseek down", true, 503));
        when(client.chat(eq(bailian), eq(SYSTEM), eq(USER)))
                .thenReturn(new ChatResult("answer-from-bailian", true, 10, 5));

        ChatFailoverOrchestrator orchestrator =
                orchestrator(client, List.of(deepseek, bailian), NO_RETRY);
        ChatOutcome outcome = orchestrator.chat(SYSTEM, USER);
        ChatResult result = outcome.result();

        assertEquals("answer-from-bailian", result.content());
        assertEquals("bailian/model-bailian", outcome.provider().displayName());
        assertTrue(result.hasUsage());
        assertEquals(10, result.inputTokens());
        assertEquals(5, result.outputTokens());

        inOrder(client).verify(client).chat(eq(deepseek), eq(SYSTEM), eq(USER));
        inOrder(client).verify(client).chat(eq(bailian), eq(SYSTEM), eq(USER));
    }

    @Test
    void retriesWithinProviderBeforeFailingOver() {
        ChatProvider deepseek = provider("deepseek");
        ChatProvider bailian = provider("bailian");
        OpenAiChatClient client = mock(OpenAiChatClient.class);

        when(client.chat(eq(deepseek), eq(SYSTEM), eq(USER)))
                .thenThrow(new LlmCallException("transient 500", true, 500))
                .thenReturn(new ChatResult("recovered-on-deepseek", false, 0, 0));

        ChatFailoverOrchestrator orchestrator =
                orchestrator(client, List.of(deepseek, bailian), 1);
        ChatResult result = orchestrator.chat(SYSTEM, USER).result();

        assertEquals("recovered-on-deepseek", result.content());
        verify(client, times(2)).chat(eq(deepseek), eq(SYSTEM), eq(USER));
        verify(client, never()).chat(eq(bailian), eq(SYSTEM), eq(USER));
    }

    @Test
    void deterministicErrorDoesNotRetryButFallsBack() {
        ChatProvider deepseek = provider("deepseek");
        ChatProvider bailian = provider("bailian");
        OpenAiChatClient client = mock(OpenAiChatClient.class);

        /* 400 属确定性错误：同一 provider 内不应重试，但允许切到备用 */
        when(client.chat(eq(deepseek), eq(SYSTEM), eq(USER)))
                .thenThrow(new LlmCallException("bad request", false, 400));
        when(client.chat(eq(bailian), eq(SYSTEM), eq(USER)))
                .thenReturn(new ChatResult("answer-from-bailian", true, 3, 7));

        ChatFailoverOrchestrator orchestrator =
                orchestrator(client, List.of(deepseek, bailian), 2);
        ChatOutcome outcome = orchestrator.chat(SYSTEM, USER);

        assertEquals("answer-from-bailian", outcome.result().content());
        assertEquals("bailian/model-bailian", outcome.provider().displayName());
        verify(client, times(1)).chat(eq(deepseek), eq(SYSTEM), eq(USER));
        verify(client, times(1)).chat(eq(bailian), eq(SYSTEM), eq(USER));
    }

    @Test
    void throwsFriendlyMessageWhenAllProvidersExhausted() {
        ChatProvider deepseek = provider("deepseek");
        ChatProvider bailian = provider("bailian");
        OpenAiChatClient client = mock(OpenAiChatClient.class);

        when(client.chat(eq(deepseek), eq(SYSTEM), eq(USER)))
                .thenThrow(new LlmCallException("deepseek down", true, 503));
        when(client.chat(eq(bailian), eq(SYSTEM), eq(USER)))
                .thenThrow(new LlmCallException("bailian down", true, 500));

        ChatFailoverOrchestrator orchestrator =
                orchestrator(client, List.of(deepseek, bailian), NO_RETRY);

        LlmExhaustedException ex = assertThrows(LlmExhaustedException.class,
                () -> orchestrator.chat(SYSTEM, USER));
        assertTrue(ex.getMessage().contains("暂时不可用"));
        assertTrue(ex.getCause() instanceof LlmCallException);
    }

    @Test
    void streamFailsBeforeFirstTokenThenFallsBack() {
        ChatProvider deepseek = provider("deepseek");
        ChatProvider bailian = provider("bailian");
        OpenAiChatClient client = mock(OpenAiChatClient.class);

        /* started 未被置位（未下发任何 token），异常可触发降级 */
        when(client.streamChat(eq(deepseek), eq(SYSTEM), eq(USER), any(), any(AtomicBoolean.class)))
                .thenThrow(new LlmCallException("connect refused", true, null));
        when(client.streamChat(eq(bailian), eq(SYSTEM), eq(USER), any(), any(AtomicBoolean.class)))
                .thenReturn(new ChatResult("stream-from-bailian", false, 0, 0));

        ChatFailoverOrchestrator orchestrator =
                orchestrator(client, List.of(deepseek, bailian), NO_RETRY);
        ChatOutcome outcome = orchestrator.chatStream(SYSTEM, USER, token -> {
        });

        assertEquals("stream-from-bailian", outcome.result().content());
        assertEquals("bailian/model-bailian", outcome.provider().displayName());
        verify(client).streamChat(eq(bailian), eq(SYSTEM), eq(USER), any(), any(AtomicBoolean.class));
    }

    @Test
    void streamInterruptedAfterStartIsNotRetriedOrFallenBack() {
        ChatProvider deepseek = provider("deepseek");
        ChatProvider bailian = provider("bailian");
        OpenAiChatClient client = mock(OpenAiChatClient.class);

        /* 已向 onDelta 下发过 token（started=true）后中断，禁止回滚，直接上抛 */
        when(client.streamChat(eq(deepseek), eq(SYSTEM), eq(USER), any(), any(AtomicBoolean.class)))
                .thenAnswer(invocation -> {
                    AtomicBoolean started = invocation.getArgument(4);
                    started.set(true);
                    throw new LlmCallException("connection lost mid-stream", true, null);
                });

        ChatFailoverOrchestrator orchestrator =
                orchestrator(client, List.of(deepseek, bailian), NO_RETRY);

        LlmExhaustedException ex = assertThrows(LlmExhaustedException.class,
                () -> orchestrator.chatStream(SYSTEM, USER, token -> {
                }));
        assertTrue(ex.getMessage().contains("暂时不可用"));
        assertTrue(ex.getCause() instanceof LlmCallException);
        assertTrue(ex.getCause().getMessage().contains("mid-stream"));
        verify(client, times(1)).streamChat(any(), any(), any(), any(), any());
        verify(client, never()).streamChat(eq(bailian), any(), any(), any(), any());
    }
}
