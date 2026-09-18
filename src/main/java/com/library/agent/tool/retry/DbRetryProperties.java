package com.library.agent.tool.retry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据库工具重试配置。
 * <p>
 * 对应 application.yaml 中 {@code agent.tool} 下的 {@code retry-*} 配置项，命名与
 * {@code agent.llm.retry-*} 对齐。
 * <p>
 * 重试预算存在的意义是把重试总耗时压在 {@code agent.tool.timeout-seconds} 之内：
 * 第二层用 {@code CompletableFuture.orTimeout} 控制工具执行时长，但它**不会中断**
 * 正在阻塞的 JDBC 调用，超时的调用仍会占着工具执行线程与池化连接。因此宁可少重试，
 * 也不能撞穿该上限。
 *
 * @author 郑钦
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.tool")
public class DbRetryProperties {

    /**
     * 额外重试次数（不含首次），语义与 {@code agent.llm.retry-attempts} 一致。
     * <p>
     * 默认 1 而非 LLM 的 2：工具墙钟预算远紧于 LLM，且真实世界的主导故障
     * （连接池中的死连接）一次瞬时重试即可救回。
     */
    private int retryAttempts = 1;

    /**
     * 线性退避基准毫秒：第 n 次重试前等待 retryBackoffMs * n。
     */
    private long retryBackoffMs = 200;

    /**
     * 单次工具调用内允许重试的墙钟预算（毫秒），须显著小于 timeout-seconds。
     */
    private long retryDeadlineMs = 15000;

    /**
     * 允许再发起一次重试所需的最小剩余预算（毫秒）。
     */
    private long retryMinAttemptMs = 5000;
}
