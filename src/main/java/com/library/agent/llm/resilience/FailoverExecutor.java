package com.library.agent.llm.resilience;

import com.library.agent.llm.LlmExhaustedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 通用三级容错内核：provider 顺序遍历 → 熔断判定 → 同 provider 有限退避重试 → 降级下一个 provider。
 * <p>
 * 被两条调用链路共用：自研 HTTP 客户端（{@code ChatFailoverOrchestrator}）与
 * langchain4j ChatModel（{@code ChatModelFailoverOrchestrator}）。
 * 内核只负责"按什么顺序、重试几次、什么时候熔断"，"什么算可重试错误"由调用方通过
 * {@link FailurePolicy} 注入，从而让不同异常体系（HTTP 状态码 / langchain4j 类型化异常）
 * 复用同一套容错语义。
 * <p>
 * 只有可重试故障（429/5xx/网络超时）才在 provider 之间降级；确定性故障（4xx 参数/鉴权）
 * 换 provider 也修不好，故立即终止，不重试也不降级。
 * <p>
 * 终止与耗尽都抛 {@link LlmExhaustedException}，对外仅暴露统一友好文案，
 * 技术细节保留在 cause 中并已写日志，避免泄露给最终用户。
 *
 * @param <P> provider 类型，如 ChatProvider / NamedChatModel
 */
public final class FailoverExecutor<P> {

    private static final Logger log = LoggerFactory.getLogger(FailoverExecutor.class);

    /**
     * 单次失败的处置方式，由调用方按异常自行判定。
     */
    public enum Disposition {
        /**
         * 瞬时故障（429/5xx/网络超时）：同 provider 退避重试，重试耗尽后计入熔断并降级。
         */
        RETRY,
        /**
         * 其余一切故障：确定性故障（4xx 参数/鉴权）与不可回滚的故障（如流式响应已下发 token）。
         * <p>
         * 立即终止，既不重试也不降级：确定性错误换 provider 只会原样复现，
         * 而流式已下发的 token 无法回滚，重试会造成前后内容割裂。
         */
        ABORT
    }

    /**
     * 一次真实调用。实现方自行决定调用哪个下游、如何抛异常。
     */
    @FunctionalInterface
    public interface Call<P, R> {
        R call(P provider);
    }

    /**
     * 失败分类策略，与具体异常体系绑定。
     * <p>
     * 入参为 {@link Throwable} 而非具体异常类型：两条链路抛出的异常体系不同
     * （HTTP 状态码异常 / langchain4j 类型化异常），由策略内部自行判定；
     * 无法识别的异常应由策略归为 {@link Disposition#ABORT}，避免既重试又熔断健康 provider。
     */
    @FunctionalInterface
    public interface FailurePolicy {
        Disposition classify(Throwable error);
    }

    /**
     * 成功结果：命中的 provider 与其返回值。
     */
    public record Attempt<P, R>(P provider, R result) {
    }

    private final List<P> providers;
    private final List<CircuitBreaker> breakers;
    private final Function<P, String> labelOf;
    private final int retryAttempts;
    private final long backoffMs;

    /**
     * @param providers               按优先级排列的 provider 列表，下标越小越优先
     * @param labelOf                 取 provider 展示名，仅用于日志
     * @param retryAttempts           单个 provider 内的额外重试次数（不含首次）
     * @param backoffMs               线性退避基准毫秒
     * @param circuitFailureThreshold 连续可重试失败达到该值即熔断该 provider
     * @param circuitOpenMs           熔断持续时长，到期后进入半开单探针
     */
    public FailoverExecutor(List<P> providers, Function<P, String> labelOf,
                            int retryAttempts, long backoffMs,
                            int circuitFailureThreshold, long circuitOpenMs) {
        this.providers = List.copyOf(providers);
        this.labelOf = labelOf;
        this.retryAttempts = retryAttempts;
        this.backoffMs = backoffMs;
        this.breakers = new ArrayList<>();
        for (int i = 0; i < this.providers.size(); i++) {
            breakers.add(new CircuitBreaker(circuitFailureThreshold, circuitOpenMs));
        }
    }

    /**
     * 依次尝试各 provider 直到成功。
     *
     * @param scene  场景标识，仅用于日志（chat/stream/react 等）
     * @param call   一次真实调用
     * @param policy 失败分类策略
     * @return 命中 provider 与调用结果
     * @throws LlmExhaustedException 所有 provider 均失败，或遇 ABORT 终止
     */
    public <R> Attempt<P, R> execute(String scene, Call<P, R> call, FailurePolicy policy) {
        Throwable last = null;
        for (int i = 0; i < providers.size(); i++) {
            P provider = providers.get(i);
            CircuitBreaker breaker = breakers.get(i);
            if (!breaker.tryAcquire()) {
                log.warn("Skip LLM provider={}, scene={}, circuit is open", labelOf.apply(provider), scene);
                continue;
            }
            for (int attempt = 0; attempt <= retryAttempts; attempt++) {
                try {
                    return new Attempt<>(provider, call.call(provider));
                } catch (RuntimeException e) {
                    Disposition disposition = policy.classify(e);
                    log.warn("LLM provider={} scene={} failed attempt={}, disposition={}, detail={}",
                            labelOf.apply(provider), scene, attempt + 1, disposition, e.getMessage());
                    if (disposition == Disposition.ABORT) {
                        /* 确定性故障或已下发 token：直接上抛友好文案，不重试也不降级 */
                        log.error("LLM provider={} scene={} aborted, no retry/failover",
                                labelOf.apply(provider), scene, e);
                        throw new LlmExhaustedException(LlmExhaustedException.USER_FACING_MESSAGE, e);
                    }
                    if (attempt < retryAttempts) {
                        sleepBackoff(attempt);
                        continue;
                    }
                    /* 只有走到这里才是"可重试但次数已耗尽"，计入熔断并降级下一个 provider */
                    breaker.onFailure();
                    last = e;
                    break;
                }
            }
        }
        log.error("All LLM providers exhausted, scene={}, lastError={}",
                scene, last == null ? "n/a" : last.getMessage());
        throw new LlmExhaustedException(LlmExhaustedException.USER_FACING_MESSAGE, last);
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
