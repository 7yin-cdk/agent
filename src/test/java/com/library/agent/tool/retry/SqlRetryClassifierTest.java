package com.library.agent.tool.retry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故障分类器单测：可重试 SQLState、确定性 SQLState、cause 链深度、
 * 以及"连接级"口径刻意窄于"可重试"这两条边界。
 * <p>
 * 判定顺序与两个口径的差异是本类的核心契约：{@code 55P03}/{@code 57014}
 * 可重试但不算连接级，{@code UnknownHostException} 两者都不算。
 */
class SqlRetryClassifierTest {

    private static SQLException state(String sqlState, String message) {
        return new SQLException(message, sqlState);
    }

    @ParameterizedTest
    @ValueSource(strings = {"08000", "08001", "08003", "08004", "08006",
            "57P01", "57P02", "57P03", "53300"})
    void connectionStatesAreRetryableAndConnectionLevel(String sqlState) {
        SQLException error = state(sqlState, "连接类故障");
        assertTrue(SqlRetryClassifier.isRetryable(error), sqlState + " 应可重试");
        assertTrue(SqlRetryClassifier.isConnectionLevel(error), sqlState + " 应视为连接级");
    }

    @Test
    void lockNotAvailableIsRetryableButNotConnectionLevel() {
        SQLException error = state("55P03", "could not obtain lock on row");
        assertTrue(SqlRetryClassifier.isRetryable(error), "锁不可用应可重试");
        assertFalse(SqlRetryClassifier.isConnectionLevel(error),
                "锁不可用是语句级错误，内层必须继续降级为局部错误节点");
    }

    @ParameterizedTest
    @ValueSource(strings = {"28P01", "28000", "3D000", "42P01", "42703", "42601", "22023", "57014"})
    void fatalStatesAreNeverRetryable(String sqlState) {
        SQLException error = state(sqlState, "确定性故障");
        assertFalse(SqlRetryClassifier.isRetryable(error), sqlState + " 不应重试");
        assertFalse(SqlRetryClassifier.isConnectionLevel(error), sqlState + " 不应视为连接级");
    }

    @Test
    void queryCanceledIsNeitherRetryableNorConnectionLevel() {
        SQLException error = state("57014", "canceling statement due to statement timeout");
        assertFalse(SqlRetryClassifier.isRetryable(error),
                "同一预算内重试必然复现同一个超时，只会白烧预算");
        assertFalse(SqlRetryClassifier.isConnectionLevel(error));
    }

    @Test
    void classifiesExceptionNestedTwoLevelsDeep() {
        Throwable wrapped = new RuntimeException("pool init failed",
                new RuntimeException(new ConnectException("Connection refused")));
        assertTrue(SqlRetryClassifier.isRetryable(wrapped),
                "Hikari 会包装底层异常，分类必须沿 cause 链追溯");
        assertTrue(SqlRetryClassifier.isConnectionLevel(wrapped));
    }

    @Test
    void fatalStateWinsOverConnectionWrapper() {
        Throwable wrapped = new RuntimeException("pool init failed", state("28P01", "password authentication failed"));
        assertFalse(SqlRetryClassifier.isRetryable(wrapped),
                "FATAL 判定必须早于类型判定，否则被 Hikari 包装后会被误判为可重试");
    }

    @Test
    void unknownHostIsNeverRetryable() {
        UnknownHostException error = new UnknownHostException("typod-host");
        assertFalse(SqlRetryClassifier.isRetryable(error),
                "DNS/主机名错误不会在重试预算内自愈");
        assertFalse(SqlRetryClassifier.isConnectionLevel(error));
    }

    @Test
    void socketTimeoutAndPoolExhaustionAreRetryable() {
        assertTrue(SqlRetryClassifier.isRetryable(new SocketTimeoutException("Read timed out")));
        assertTrue(SqlRetryClassifier.isRetryable(
                new SQLTransientConnectionException("Connection is not available, request timed out")));
    }

    @Test
    void deadConnectionMessageIsRecognizedByText() {
        SQLException error = new SQLException("This connection has been closed.", "08003");
        assertTrue(SqlRetryClassifier.isConnectionLevel(error));
        assertTrue(SqlRetryClassifier.isRetryable(error));
    }

    @Test
    void unrecognizedFailureFailsClosed() {
        assertFalse(SqlRetryClassifier.isRetryable(new RuntimeException("some unknown boom")));
        assertFalse(SqlRetryClassifier.isConnectionLevel(new RuntimeException("some unknown boom")));
    }

    @Test
    void rethrowIfConnectionLevelWrapsConnectionFailure() {
        SQLException error = state("08006", "An I/O error occurred while sending to the backend");
        DbTransientException thrown = assertThrows(DbTransientException.class,
                () -> SqlRetryClassifier.rethrowIfConnectionLevel(error));
        assertNotNull(thrown.getCause(), "包装异常必须保留原始 cause，供重试内核继续分类");
        assertTrue(SqlRetryClassifier.isRetryable(thrown), "包装后的异常仍应可重试");
    }

    @Test
    void rethrowIfConnectionLevelLetsDataFailurePass() {
        SQLException error = state("42P01", "relation \"foo\" does not exist");
        SqlRetryClassifier.rethrowIfConnectionLevel(error);
    }
}
