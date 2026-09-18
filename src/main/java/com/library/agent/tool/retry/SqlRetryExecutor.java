package com.library.agent.tool.retry;

import lombok.extern.slf4j.Slf4j;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * 有界重试内核：仅对可重试的瞬时故障按线性退避重试，并受单次工具调用的墙钟预算约束。
 * <p>
 * 与 {@code llm/resilience/FailoverExecutor} 同源：同为"分类器注入 + 线性退避 + 有界次数"，
 * 且 {@code retryAttempts} 语义一致（额外重试次数，不含首次）。未直接复用该类的原因是它
 * 绑定了 {@code LlmExhaustedException} 与 provider 列表模型，对单目标重试不适用。
 * <p>
 * 预算闸门只能阻止**发起**重试，无法抢占已在飞行中的 JDBC 调用——这是上层
 * {@code orTimeout} 的固有限制，故宁可放弃重试也不能撞穿工具执行超时上限。
 *
 * @author 郑钦
 */
@Slf4j
public final class SqlRetryExecutor {

    /**
     * 一次数据库工作单元。实现方须保证幂等——瞬时故障会重跑整个工作单元。
     *
     * @param <T> 工作单元的返回类型
     */
    @FunctionalInterface
    public interface DbWork<T> {

        /**
         * 执行一次数据库访问。
         *
         * @return 本次访问的结果
         * @throws Exception 任何失败，由重试内核分类
         */
        T run() throws Exception;
    }

    private final int retryAttempts;
    private final long backoffMs;
    private final long deadlineMs;
    private final long minAttemptMs;
    private final LongSupplier clock;
    private final LongConsumer sleeper;

    public SqlRetryExecutor(DbRetryProperties properties) {
        this(properties.getRetryAttempts(), properties.getRetryBackoffMs(),
                properties.getRetryDeadlineMs(), properties.getRetryMinAttemptMs(),
                System::nanoTime, SqlRetryExecutor::sleep);
    }

    /**
     * 测试可见构造器：注入假时钟与假睡眠，使预算判定与退避可确定性验证。
     *
     * @param retryAttempts 额外重试次数（不含首次）
     * @param backoffMs     线性退避基准毫秒
     * @param deadlineMs    墙钟预算
     * @param minAttemptMs  允许再试一次所需的最小剩余预算
     * @param clock         纳秒时间源
     * @param sleeper       毫秒睡眠实现
     */
    SqlRetryExecutor(int retryAttempts, long backoffMs, long deadlineMs, long minAttemptMs,
                     LongSupplier clock, LongConsumer sleeper) {
        this.retryAttempts = retryAttempts;
        this.backoffMs = backoffMs;
        this.deadlineMs = deadlineMs;
        this.minAttemptMs = minAttemptMs;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * 在墙钟预算内执行工作单元。
     *
     * @param scene       场景标识，仅用于日志
     * @param work        一次真实数据库访问
     * @param beforeRetry 每次重试前的清理动作（如清理失效连接池），无清理需求时传空实现
     * @param <T>         工作单元返回类型
     * @return 首次成功的结果
     * @throws Exception 非可重试故障原样上抛；可重试故障在重试耗尽或预算不足后上抛最后一次
     */
    public <T> T execute(String scene, DbWork<T> work, Runnable beforeRetry) throws Exception {
        long start = clock.getAsLong();
        Exception last = null;
        for (int attempt = 0; attempt <= retryAttempts; attempt++) {
            if (attempt > 0) {
                if (!budgetAllows(scene, attempt, start)) {
                    break;
                }
                sleeper.accept(backoffMs * attempt);
            }
            try {
                return work.run();
            } catch (Exception e) {
                if (!SqlRetryClassifier.isRetryable(e)) {
                    throw e;
                }
                last = e;
                log.warn("数据库工具瞬时故障 scene={} attempt={}/{} detail={}",
                        scene, attempt + 1, retryAttempts + 1, e.getMessage());
                if (attempt < retryAttempts) {
                    beforeRetry.run();
                }
            }
        }
        log.warn("数据库工具重试结束 scene={} detail={}", scene,
                last == null ? "n/a" : last.getMessage());
        throw last;
    }

    /**
     * 预算判定：退避与下一次尝试之后是否仍留够最小预留。
     */
    private boolean budgetAllows(String scene, int attempt, long start) {
        long elapsedMs = (clock.getAsLong() - start) / 1_000_000;
        long needMs = elapsedMs + backoffMs * attempt + minAttemptMs;
        if (needMs > deadlineMs) {
            log.warn("数据库工具重试预算不足 scene={} elapsed={}ms need={}ms budget={}ms",
                    scene, elapsedMs, needMs, deadlineMs);
            return false;
        }
        return true;
    }

    /**
     * 线性退避；被中断时恢复中断标志并以运行时异常结束，交由调用方降级。
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DbTransientException("数据库工具重试等待被中断", e);
        }
    }
}
