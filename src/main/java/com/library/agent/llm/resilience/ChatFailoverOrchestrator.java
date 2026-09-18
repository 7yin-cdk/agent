package com.library.agent.llm.resilience;

import com.library.agent.llm.client.ChatProvider;
import com.library.agent.llm.client.ChatResult;
import com.library.agent.llm.client.LlmCallException;
import com.library.agent.llm.client.OpenAiChatClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 链路一（自研 OpenAI 兼容 HTTP 客户端）的容错编排器：重试 → 降级下一个 provider → 熔断。
 * <p>
 * 容错循环本体已抽到 {@link FailoverExecutor}，本类只负责把"自研客户端的失败语义"
 * 翻译成统一的失败处置策略：{@link LlmCallException#isRetryable()} 判定 429/5xx 与
 * 网络超时为可重试，其余 4xx 等确定性错误立即终止（不重试也不降级）。
 * <p>
 * 流式特例：仅"首个内容 token 下发前"的失败允许重试/降级；一旦向 onDelta 下发了
 * token（started=true）即视为不可回滚，以 {@link FailoverExecutor.Disposition#ABORT} 立即终止。
 */
public class ChatFailoverOrchestrator {

    private static final String SCENE_CHAT = "chat";
    private static final String SCENE_STREAM = "stream";

    private final OpenAiChatClient client;
    private final FailoverExecutor<ChatProvider> executor;

    public ChatFailoverOrchestrator(OpenAiChatClient client, List<ChatProvider> providers,
                                    int retryAttempts, long backoffMs,
                                    int circuitFailureThreshold, long circuitOpenMs) {
        this.client = client;
        this.executor = new FailoverExecutor<>(providers, ChatProvider::displayName,
                retryAttempts, backoffMs, circuitFailureThreshold, circuitOpenMs);
    }

    /**
     * 非流式调用，返回成功 provider 与结果。
     */
    public ChatOutcome chat(String systemPrompt, String userPrompt) {
        FailoverExecutor.Attempt<ChatProvider, ChatResult> attempt = executor.execute(SCENE_CHAT,
                provider -> client.chat(provider, systemPrompt, userPrompt),
                e -> classify(e, false));
        return new ChatOutcome(attempt.provider(), attempt.result());
    }

    /**
     * 流式调用：把 token 实时回调给 onDelta；仅首 token 前失败可重试/切换 provider。
     */
    public ChatOutcome chatStream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        /* started 在所有 provider 尝试间共享：一旦置位必然 ABORT 终止，
         * 因此与"每次尝试新建标志"语义等价，无需按尝试重置 */
        AtomicBoolean started = new AtomicBoolean(false);
        FailoverExecutor.Attempt<ChatProvider, ChatResult> attempt = executor.execute(SCENE_STREAM,
                provider -> client.streamChat(provider, systemPrompt, userPrompt, onDelta, started),
                e -> classify(e, started.get()));
        return new ChatOutcome(attempt.provider(), attempt.result());
    }

    /**
     * 失败处置判定。
     * <p>
     * 只有 {@link LlmCallException#isRetryable()} 为真的 429/5xx/网络超时才重试并允许降级；
     * 其余一律 ABORT —— 4xx 等确定性错误换 provider 只会原样复现，
     * 已下发 token 的流式中断不可回滚，非 LlmCallException 的意外异常则无从判断可恢复性，
     * 三者都收敛为统一友好文案而非把内部细节上抛。
     */
    private static FailoverExecutor.Disposition classify(Throwable error, boolean streamStarted) {
        if (streamStarted) {
            return FailoverExecutor.Disposition.ABORT;
        }
        return error instanceof LlmCallException llm && llm.isRetryable()
                ? FailoverExecutor.Disposition.RETRY : FailoverExecutor.Disposition.ABORT;
    }
}
