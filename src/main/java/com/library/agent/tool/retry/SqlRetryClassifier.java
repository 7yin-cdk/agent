package com.library.agent.tool.retry;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.io.EOFException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 数据库故障分类器：只回答"这次失败属于哪一类"，不涉及退避与预算。
 * <p>
 * 对外提供三个判定，三者刻意保持不同口径：
 * <ul>
 *   <li>{@link #isRetryable}：重试内核用，判定该不该重试；</li>
 *   <li>{@link #isConnectionLevel}：内层降级用，判定是否属于"连接本身不可用"；</li>
 *   <li>{@link #rethrowIfConnectionLevel}：内层 catch 调用的守卫。</li>
 * </ul>
 * {@code isConnectionLevel} 比 {@code isRetryable} **更窄**：不含 {@code 55P03}（锁不可用）
 * 与 {@code 57014}（查询被取消）这类语句级错误。若把两者混同，一次普通的锁超时就会中断
 * 整个工具、丢掉本来能正常返回的部分数据，属于行为回归。
 * <p>
 * 判定顺序：先确定性故障（FATAL），再异常类型，最后消息文本兜底。把 FATAL 放在最前是防护性的
 * ——避免 {@code PSQLException(28P01)} 被 Hikari 的 {@code PoolInitializationException}
 * 包一层之后被误判成可重试的建连失败。无法识别的异常一律不重试（fail-closed），与
 * {@code FailoverExecutor} 的"未识别即 ABORT"保持一致。
 *
 * @author 郑钦
 */
public final class SqlRetryClassifier {

    /**
     * cause 链最大追溯深度，防御异常链自引用。
     */
    private static final int MAX_CAUSE_DEPTH = 10;

    /**
     * 连接级 SQLState：连接本身不可用，重试可自愈，内层命中即上抛。
     */
    private static final Set<String> CONNECTION_STATES = Set.of(
            "08000", "08001", "08003", "08004", "08006",
            "57P01", "57P02", "57P03", "53300");

    /**
     * 语句级但语义上可重试的 SQLState：不视为连接级，内层仍降级为局部错误节点。
     */
    private static final Set<String> RETRY_EXTRA_STATES = Set.of("55P03");

    /**
     * 确定性 SQLState：命中即终止，绝不重试。
     * <p>
     * {@code 28P01/28000} 鉴权失败、{@code 3D000} 库不存在、{@code 42P01/42703/42601}
     * schema 与 SQL 缺陷、{@code 22023} 参数值非法，重试永远不可能成功；
     * {@code 57014} 查询被取消的主因是 statement_timeout / pg_cancel_backend，
     * 同一预算内重试必然复现同一个超时，只会白烧预算。
     */
    private static final Set<String> FATAL_STATES = Set.of(
            "28P01", "28000", "3D000", "42P01", "42703", "42601", "22023", "57014");

    /**
     * 连接失效文案兜底：部分连接故障没有可靠的 SQLState（如 Hikari 关闭连接），
     * 只能靠驱动消息识别。
     */
    private static final List<String> DEAD_CONNECTION_HINTS = List.of(
            "this connection has been closed",
            "connection is closed",
            "an i/o error occurred while sending to the backend",
            "connection reset",
            "broken pipe");

    private SqlRetryClassifier() {
    }

    /**
     * 判定异常是否值得重试。
     *
     * @param error 工具执行过程中抛出的异常
     * @return true 表示属于瞬时故障，可退避重试
     */
    public static boolean isRetryable(Throwable error) {
        if (isFatal(error) || chainMatches(error, UnknownHostException.class::isInstance)) {
            return false;
        }
        return isConnectionLevel(error) || chainMatches(error, inStates(RETRY_EXTRA_STATES));
    }

    /**
     * 判定异常是否源于"连接本身不可用"。
     *
     * @param error 工具执行过程中抛出的异常
     * @return true 表示应上抛给重试内核，而不是降级为局部错误
     */
    public static boolean isConnectionLevel(Throwable error) {
        if (isFatal(error)) {
            return false;
        }
        if (chainMatches(error, inStates(CONNECTION_STATES)) || hitsConnectionType(error)) {
            return true;
        }
        return chainMatches(error, t -> matchesDeadConnectionHint(t.getMessage()));
    }

    /**
     * 内层降级守卫：连接级故障包装上抛以触发重试，数据级故障原样返回由调用方降级。
     *
     * @param error 内层 catch 捕获到的异常
     */
    public static void rethrowIfConnectionLevel(Throwable error) {
        if (isConnectionLevel(error)) {
            throw new DbTransientException(messageOf(error), error);
        }
    }

    // ======================== 内部判定 ========================

    /**
     * 是否为确定性故障；{@link DbTransientException} 先还原其 cause 再判定。
     */
    private static boolean isFatal(Throwable error) {
        return chainMatches(unwrap(error), inStates(FATAL_STATES));
    }

    /**
     * cause 链上是否存在连接/网络类异常。
     */
    private static boolean hitsConnectionType(Throwable error) {
        return chainMatches(error, t -> t instanceof ConnectException
                || t instanceof SocketTimeoutException
                || t instanceof SocketException
                || t instanceof EOFException
                || t instanceof SQLTransientConnectionException);
    }

    /**
     * 沿 cause 链应用判定谓词，命中即返回；用深度上限防御自引用链。
     */
    private static boolean chainMatches(Throwable error, Predicate<Throwable> predicate) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH;
             depth++, current = current.getCause()) {
            if (predicate.test(current)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构造"cause 链上存在某 SQLException 且其 SQLState 命中给定集合"的谓词。
     */
    private static Predicate<Throwable> inStates(Set<String> states) {
        return t -> t instanceof SQLException && states.contains(stateOf((SQLException) t));
    }

    /**
     * {@link DbTransientException} 还原为被包装的 cause，使 SQLState 判定能看到原始异常。
     */
    private static Throwable unwrap(Throwable error) {
        return error instanceof DbTransientException && error.getCause() != null
                ? error.getCause() : error;
    }

    private static String stateOf(SQLException e) {
        return e.getSQLState() == null ? "" : e.getSQLState();
    }

    private static boolean matchesDeadConnectionHint(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String hint : DEAD_CONNECTION_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static String messageOf(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
