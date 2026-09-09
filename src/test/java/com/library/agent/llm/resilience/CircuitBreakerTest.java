package com.library.agent.llm.resilience;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔断器状态机单测：阈值熔断、时间窗到期单探针、探针失败重熔断、并发仅单探针放行。
 * <p>
 * 通过包私有构造器注入可控时钟，不依赖真实时间。
 */
class CircuitBreakerTest {

    /* 可控时钟：测试内手动推进 now */
    private static final class FakeClock implements CircuitBreaker.LongSupplier {
        private final AtomicLong now = new AtomicLong(0);

        @Override
        public long get() {
            return now.get();
        }

        void advance(long millis) {
            now.addAndGet(millis);
        }
    }

    @Test
    void closesAfterThresholdFailures() {
        FakeClock clock = new FakeClock();
        CircuitBreaker cb = new CircuitBreaker(3, 10_000, clock);

        assertTrue(cb.tryAcquire(), "CLOSED 初始应放行");

        cb.onFailure();
        cb.onFailure();
        assertEquals(2, cb.consecutiveFailures());
        assertTrue(cb.tryAcquire(), "未达阈值前应继续放行");

        cb.onFailure();
        assertTrue(cb.isOpen(), "达阈值应熔断");
        assertFalse(cb.tryAcquire(), "OPEN 期间应拒绝");
    }

    @Test
    void opensAfterExplicitDurationAndProbeRecoversOnSuccess() {
        FakeClock clock = new FakeClock();
        CircuitBreaker cb = new CircuitBreaker(2, 1_000, clock);

        cb.onFailure();
        cb.onFailure();
        assertTrue(cb.isOpen());
        assertFalse(cb.tryAcquire(), "OPEN 未到期拒绝");

        clock.advance(1_000);
        assertTrue(cb.tryAcquire(), "到期后首个请求应作为 HALF_OPEN 探针放行");
        assertFalse(cb.tryAcquire(), "HALF_OPEN 期间其它请求应拒绝");

        cb.onSuccess();
        assertFalse(cb.isOpen(), "探针成功应回 CLOSED");
        assertEquals(0, cb.consecutiveFailures());
        assertTrue(cb.tryAcquire(), "CLOSED 后应恢复放行");
    }

    @Test
    void failedProbeReopensAndRefreshesWindow() {
        FakeClock clock = new FakeClock();
        CircuitBreaker cb = new CircuitBreaker(2, 1_000, clock);

        cb.onFailure();
        cb.onFailure();
        clock.advance(1_000);
        assertTrue(cb.tryAcquire(), "探针放行");
        cb.onFailure();
        assertTrue(cb.isOpen(), "探针失败应重新 OPEN");
        assertFalse(cb.tryAcquire(), "重熔断后应拒绝（时间窗已刷新）");

        clock.advance(1_000);
        assertTrue(cb.tryAcquire(), "重熔断窗口到期后再次放行探针");
    }

    @Test
    void onlySingleProbePassesUnderConcurrency() throws Exception {
        FakeClock clock = new FakeClock();
        CircuitBreaker cb = new CircuitBreaker(2, 1_000, clock);

        cb.onFailure();
        cb.onFailure();
        clock.advance(1_000);

        int threads = 20;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Thread> workers = new ArrayList<>();
        List<Boolean> results = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception ignored) {
                }
                results.add(cb.tryAcquire());
            });
            workers.add(t);
            t.start();
        }
        for (Thread t : workers) {
            t.join();
        }

        long passed = results.stream().filter(Boolean::booleanValue).count();
        assertEquals(1, passed, "OPEN 到期并发下应仅单个探针放行");
    }
}
