package com.library.agent.tool;

import com.library.agent.tool.retry.DbRetryProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基类重试模板单测：验证连接级瞬时故障在工具内部自愈、确定性故障直接降级。
 * <p>
 * 接缝是 {@link AbstractPostgresTool#connectionSupplier}：覆写它即可完全绕开 Hikari，
 * 用动态代理伪造会抛异常的 Connection。假连接抛出的必须是受检的 {@link SQLException}
 * 子类——代理处理器里抛 IOException 会被包成 UndeclaredThrowableException，破坏分类。
 */
class AbstractPostgresToolRetryTest {

    /**
     * 假工具：按脚本逐次决定"取到的连接"是否可用，并统计清理次数。
     */
    private static final class FakeTool extends AbstractPostgresTool {

        /* 每次取连接时弹出一个故障；队列耗尽或元素为 null 表示本次成功 */
        private final Deque<Throwable> script = new ArrayDeque<>();

        private int connectionAttempts;
        private int evictCount;

        FakeTool() {
            retryProperties = new DbRetryProperties();
            retryProperties.setRetryAttempts(1);
            retryProperties.setRetryBackoffMs(1);
            retryProperties.setRetryDeadlineMs(15_000);
            retryProperties.setRetryMinAttemptMs(0);
        }

        FakeTool scriptOf(Throwable... failures) {
            for (Throwable failure : failures) {
                script.add(failure);
            }
            return this;
        }

        @Override
        protected ConnectionSupplier connectionSupplier(String instance, String database) {
            return () -> {
                connectionAttempts++;
                Throwable failure = script.isEmpty() ? null : script.poll();
                return fakeConnection(failure);
            };
        }

        @Override
        protected void evictPool(String instance, String database) {
            evictCount++;
        }

        String call() {
            return executeWithRetry("localhost:5432", "rag_db", "测试采集失败", conn -> {
                conn.createStatement();
                return "{\"success\":true}";
            });
        }
    }

    /**
     * 构造一个在除 close 外的所有方法上抛出指定故障的连接。
     * close 必须放行，否则 try-with-resources 的关闭动作会顶替掉真正的失败。
     */
    private static Connection fakeConnection(Throwable failure) {
        return (Connection) Proxy.newProxyInstance(
                AbstractPostgresToolRetryTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    if (failure == null) {
                        return null;
                    }
                    throw failure;
                });
    }

    @Test
    void recoversFromDeadConnectionOnRetry() {
        FakeTool tool = new FakeTool().scriptOf(new SQLException("This connection has been closed.", "08006"));

        String result = tool.call();

        assertEquals("{\"success\":true}", result, "死连接应在工具内部换连接重试后成功");
        assertEquals(2, tool.connectionAttempts, "首次失败 + 一次重试");
        assertEquals(1, tool.evictCount, "重试前应清理一次失效连接池");
    }

    @Test
    void degradesDeterministicFailureWithoutRetry() {
        FakeTool tool = new FakeTool().scriptOf(new SQLException("syntax error at or near \"x\"", "42601"));

        String result = tool.call();

        assertTrue(result.contains("\"success\":false"), "确定性故障应降级为结构化错误");
        assertTrue(result.contains("测试采集失败"), "错误应带上场景前缀");
        assertEquals(1, tool.connectionAttempts, "确定性故障一次都不重试");
        assertEquals(1, tool.evictCount, "最终失败时仍应清理一次连接池");
    }

    @Test
    void structuredErrorAfterRetriesExhausted() {
        FakeTool tool = new FakeTool().scriptOf(
                new SQLException("An I/O error occurred while sending to the backend", "08006"),
                new SQLException("An I/O error occurred while sending to the backend", "08006"));

        String result = tool.call();

        assertTrue(result.contains("\"success\":false"), "重试耗尽后应返回结构化错误而不是抛给 LLM");
        assertEquals(2, tool.connectionAttempts, "首次 + 一次重试即耗尽");
        assertEquals(2, tool.evictCount, "一次在重试前，一次在最终失败时");
    }
}
