package com.library.agent.llm.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 轻量单实例熔断器（无外部依赖）。
 * <p>
 * 状态机：CLOSED(正常) → OPEN(熔断) → HALF_OPEN(半开单探针) → CLOSED/OPEN。
 * <ul>
 *   <li>CLOSED 下连续失败达到阈值 → OPEN，持续 openDurationMs 后下一次请求经
 *       CAS 置为 HALF_OPEN 并作为唯一探针放行（天然保证半开单探针）；</li>
 *   <li>探针成功 → 回 CLOSED 并清零连续失败；探针失败 → 重新 OPEN 并刷新时间窗；</li>
 *   <li>OPEN 期间其余请求一律被 tryAcquire 拒绝（快速失败，跳过该 provider）。</li>
 * </ul>
 */
public class CircuitBreaker {

    private enum State {CLOSED, OPEN, HALF_OPEN}

    private final int failureThreshold;
    private final long openDurationMs;
    private final LongSupplier nowMs;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openUntilMs;

    public CircuitBreaker(int failureThreshold, long openDurationMs) {
        this(failureThreshold, openDurationMs, System::currentTimeMillis);
    }

    /* 注入时间源便于单元测试推进熔断窗口 */
    CircuitBreaker(int failureThreshold, long openDurationMs, LongSupplier nowMs) {
        if (failureThreshold < 1 || openDurationMs < 0) {
            throw new IllegalArgumentException("failureThreshold >= 1, openDurationMs >= 0");
        }
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDurationMs;
        this.nowMs = nowMs;
    }

    /**
     * 请求放行判定。
     * <p>
     * CLOSED 一律放行；OPEN 到期的第一个请求 CAS 置 HALF_OPEN 后放行（单探针），
     * 其余按拒绝处理；HALF_OPEN 期间不再放行其它请求。
     */
    public boolean tryAcquire() {
        while (true) {
            State current = state.get();
            if (current == State.CLOSED) {
                return true;
            }
            if (current == State.OPEN) {
                if (nowMs.get() < openUntilMs || !state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    return false;
                }
                return true;
            }
            /* HALF_OPEN：只有完成 OPEN→HALF_OPEN 切换的那个调用方能通过 */
            return false;
        }
    }

    /**
     * 调用成功后回 CLOSED，并清零连续失败计数。
     */
    public void onSuccess() {
        state.set(State.CLOSED);
        consecutiveFailures.set(0);
    }

    /**
     * 调用失败：CLOSED 下累计失败达到阈值则切换 OPEN；HALF_OPEN 探针失败则重新 OPEN。
     * OPEN 期间收到的失败不改变窗口（已由最早触发者维护）。
     */
    public void onFailure() {
        State current = state.get();
        if (current == State.CLOSED) {
            if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    openUntilMs = nowMs.get() + openDurationMs;
                }
            }
        } else if (current == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                consecutiveFailures.set(0);
                openUntilMs = nowMs.get() + openDurationMs;
            }
        }
    }

    /**
     * 当前是否处于熔断开启状态（用于可观测/日志）。
     */
    public boolean isOpen() {
        State current = state.get();
        return current == State.OPEN || current == State.HALF_OPEN;
    }

    /**
     * 连续失败计数（用于测试与诊断）。
     */
    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    /* 时间源函数式接口，避免直接依赖 System.currentTimeMillis 便于测试 */
    @FunctionalInterface
    interface LongSupplier {
        long get();
    }
}
