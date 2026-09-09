package com.library.agent.llm.resilience;

import com.library.agent.llm.LlmExhaustedException;
import com.library.agent.llm.client.ChatProvider;
import com.library.agent.llm.client.LlmCallException;
import com.library.agent.llm.client.OpenAiChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * LLM Chat 调用的三级容错编排器：重试 → 降级下一个 provider → 熔断。
 * <p>
 * 遍历 provider 列表（顺序即优先级）：对每个 provider 先做熔断判定（OPEN 直接跳过），
 * 再做有限退避重试；可重试错误计失败触发熔断，确定性错误（4xx 等）不计熔断但也会
 * 尝试下一个 provider。全部耗尽后抛 {@link LlmExhaustedException}（友好文案）。
 * <p>
 * 流式特例：仅"首个内容 token 下发前"的失败允许重试/降级；一旦向 onDelta 下发了
 * token（started=true）即视为不可回滚，原样向上抛，保持调用方既有行为。
 */
public class ChatFailoverOrchestrator {

    /* 统一对外文案，避免把技术细节透传给最终用户 */
    private static final String EXHAUSTED_MESSAGE = "AI 服务暂时不可用，请稍后再试。";
    private static final Logger log = LoggerFactory.getLogger(ChatFailoverOrchestrator.class);

    private final OpenAiChatClient client;
    private final List<ChatProvider> providers;
    private final List<CircuitBreaker> breakers = new ArrayList<>();
    private final int retryAttempts;
    private final long backoffMs;

    public ChatFailoverOrchestrator(OpenAiChatClient client, List<ChatProvider> providers,
                                    int retryAttempts, long backoffMs,
                                    int circuitFailureThreshold, long circuitOpenMs) {
        this.client = client;
        this.providers = List.copyOf(providers);
        this.retryAttempts = retryAttempts;
        this.backoffMs = backoffMs;
        for (int i = 0; i < this.providers.size(); i++) {
            breakers.add(new CircuitBreaker(circuitFailureThreshold, circuitOpenMs));
        }
    }

    /**
     * 非流式调用，返回成功 provider 与结果。
     */
    public ChatOutcome chat(String systemPrompt, String userPrompt) {
        LlmCallException last = null;
        for (int i = 0; i < providers.size(); i++) {
            ChatProvider provider = providers.get(i);
            CircuitBreaker breaker = breakers.get(i);
            if (!breaker.tryAcquire()) {
                log.warn("Skip LLM provider={}, circuit is open", provider.name());
                continue;
            }
            for (int attempt = 0; attempt <= retryAttempts; attempt++) {
                try {
                    return new ChatOutcome(provider, client.chat(provider, systemPrompt, userPrompt));
                } catch (LlmCallException e) {
                    log.warn("LLM provider={} chat failed attempt={}, retryable={}, detail={}",
                            provider.name(), attempt + 1, e.isRetryable(), e.getMessage());
                    boolean retryable = e.isRetryable();
                    boolean canRetry = retryable && attempt < retryAttempts;
                    if (canRetry) {
                        sleepBackoff(attempt);
                        continue;
                    }
                    if (retryable) {
                        breaker.onFailure();
                    }
                    last = e;
                    break;
                }
            }
        }
        log.error("All LLM providers exhausted, lastError={}", last == null ? "n/a" : last.getMessage());
        throw new LlmExhaustedException(EXHAUSTED_MESSAGE, last);
    }

    /**
     * 流式调用：把 token 实时回调给 onDelta；仅首 token 前失败可切换 provider。
     */
    public ChatOutcome chatStream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        LlmCallException last = null;
        for (int i = 0; i < providers.size(); i++) {
            ChatProvider provider = providers.get(i);
            CircuitBreaker breaker = breakers.get(i);
            if (!breaker.tryAcquire()) {
                log.warn("Skip LLM provider={}, circuit is open", provider.name());
                continue;
            }
            for (int attempt = 0; attempt <= retryAttempts; attempt++) {
                AtomicBoolean started = new AtomicBoolean(false);
                try {
                    return new ChatOutcome(provider, client.streamChat(provider, systemPrompt, userPrompt, onDelta, started));
                } catch (LlmCallException e) {
                    if (started.get()) {
                        /* 已下发过 token，SSE 已向用户推送，无法回滚；
                         * 但对外仍用统一友好文案，技术细节仅进日志 */
                        log.error("LLM provider={} stream interrupted after start, abort", provider.name(), e);
                        throw new LlmExhaustedException(EXHAUSTED_MESSAGE, e);
                    }
                    log.warn("LLM provider={} stream failed before first token attempt={}, retryable={}, detail={}",
                            provider.name(), attempt + 1, e.isRetryable(), e.getMessage());
                    boolean retryable = e.isRetryable();
                    boolean canRetry = retryable && attempt < retryAttempts;
                    if (canRetry) {
                        sleepBackoff(attempt);
                        continue;
                    }
                    if (retryable) {
                        breaker.onFailure();
                    }
                    last = e;
                    break;
                }
            }
        }
        log.error("All LLM providers exhausted for stream, lastError={}", last == null ? "n/a" : last.getMessage());
        throw new LlmExhaustedException(EXHAUSTED_MESSAGE, last);
    }

    /* 线性退避；被中断时恢复中断标志并以友好异常结束 */
    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(backoffMs * (attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmExhaustedException("LLM 重试等待被中断", e);
        }
    }
}
