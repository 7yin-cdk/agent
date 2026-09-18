package com.library.agent.tool.retry;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试内核单测：重试次数语义、清理钩子时序、退避额度与预算闸门。
 * <p>
 * 通过包私有构造器注入假时钟与假睡眠，全程不睡真实时间，结果确定。
 */
class SqlRetryExecutorTest {

    /* 可控时钟：手动推进纳秒，供预算判定使用 */
    private static final class FakeClock {
        private final AtomicLong nanos = new AtomicLong(0);

        long now() {
            return nanos.get();
        }

        void advanceMillis(long millis) {
            nanos.addAndGet(millis * 1_000_000);
        }
    }

    /* 记录退避额度与清理钩子的相对时序 */
    private static final List<String> events = new ArrayList<>();

    private static SqlRetryExecutor executor(int retryAttempts, long backoffMs,
                                             long deadlineMs, long minAttemptMs, FakeClock clock) {
        return new SqlRetryExecutor(retryAttempts, backoffMs, deadlineMs, minAttemptMs, clock::now, millis -> {
            events.add("sleep:" + millis);
            clock.advanceMillis(millis);
        });
    }

    private static SQLException transientError() {
        return new SQLException("This connection has been closed.", "08006");
    }

    @Test
    void retriesTransientFailureThenSucceeds() throws Exception {
        events.clear();
        FakeClock clock = new FakeClock();
        AtomicInteger workCalls = new AtomicInteger();
        AtomicInteger cleanupCalls = new AtomicInteger();

        String result = executor(1, 200, 15_000, 0, clock).execute("t", () -> {
            if (workCalls.incrementAndGet() == 1) {
                throw transientError();
            }
            return "ok";
        }, cleanupCalls::incrementAndGet);

        assertEquals("ok", result);
        assertEquals(2, workCalls.get(), "首次失败后应重试一次");
        assertEquals(1, cleanupCalls.get(), "每次重试前应清理一次失效连接池");
        assertEquals(List.of("sleep:200"), events, "退避额度应为 backoffMs * attempt，即 200 * 1");
    }

    @Test
    void doesNotRetryDeterministicFailure() {
        events.clear();
        FakeClock clock = new FakeClock();
        AtomicInteger workCalls = new AtomicInteger();
        AtomicInteger cleanupCalls = new AtomicInteger();

        SQLException fatal = new SQLException("syntax error at or near \"x\"", "42601");
        Exception thrown = assertThrows(Exception.class,
                () -> executor(1, 200, 15_000, 0, clock).execute("t", () -> {
                    workCalls.incrementAndGet();
                    throw fatal;
                }, cleanupCalls::incrementAndGet));

        assertSame(fatal, thrown, "确定性故障应原样上抛");
        assertEquals(1, workCalls.get(), "确定性故障一次都不重试");
        assertEquals(0, cleanupCalls.get(), "不重试就不应触发清理");
        assertTrue(events.isEmpty(), "不重试就不应退避");
    }

    @Test
    void exhaustedRetriesThrowLastFailure() {
        events.clear();
        FakeClock clock = new FakeClock();
        AtomicInteger workCalls = new AtomicInteger();
        AtomicInteger cleanupCalls = new AtomicInteger();

        Exception thrown = assertThrows(Exception.class,
                () -> executor(1, 200, 15_000, 0, clock).execute("t", () -> {
                    workCalls.incrementAndGet();
                    throw transientError();
                }, cleanupCalls::incrementAndGet));

        assertEquals(2, workCalls.get(), "retry-attempts=1 表示首次 + 一次重试");
        assertEquals(1, cleanupCalls.get(), "重试耗尽的一次清理在重试前，不在终点");
        assertTrue(SqlRetryClassifier.isRetryable(thrown), "耗尽后上抛的应是最后一次瞬时故障");
    }

    @Test
    void budgetGateSkipsRetryWhenTimeAlreadySpent() {
        events.clear();
        FakeClock clock = new FakeClock();
        AtomicInteger workCalls = new AtomicInteger();
        AtomicInteger cleanupCalls = new AtomicInteger();

        assertThrows(Exception.class,
                () -> executor(1, 200, 15_000, 5_000, clock).execute("t", () -> {
                    workCalls.incrementAndGet();
                    clock.advanceMillis(12_000);
                    throw transientError();
                }, cleanupCalls::incrementAndGet));

        assertEquals(1, workCalls.get(), "已耗 12s，再试需 12s+0.2s+5s > 15s，应放弃剩余重试");
        assertEquals(1, cleanupCalls.get(), "清理发生在失败之后、预算判定之前，故仍计一次");
        assertTrue(events.isEmpty(), "放弃重试则不应退避");
    }

    @Test
    void cleanupRunsBeforeBackoff() throws Exception {
        events.clear();
        FakeClock clock = new FakeClock();
        AtomicInteger workCalls = new AtomicInteger();

        executor(1, 200, 15_000, 0, clock).execute("t", () -> {
            if (workCalls.incrementAndGet() == 1) {
                throw transientError();
            }
            return "ok";
        }, () -> events.add("cleanup"));

        assertEquals(List.of("cleanup", "sleep:200"), events,
                "清理失效连接池必须发生在退避之前");
    }
}
