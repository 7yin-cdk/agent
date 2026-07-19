package com.library.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * PostgreSQL 数据库核心性能指标采集工具。
 * <p>
 * 通过 pg_stat_activity、pg_stat_database、pg_stat_user_tables、
 * pg_stat_bgwriter、pg_stat_replication 等系统视图，
 * 采集实例级别和数据库级别的八项关键性能指标。
 */
@Component
public class DatabaseMetricsTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 连接池缓存，key = "instance/database"，复用同一实例的连接池。
     */
    private final ConcurrentMap<String, HikariDataSource> poolCache = new ConcurrentHashMap<>();

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @PreDestroy
    public void closeAllPools() {
        for (HikariDataSource ds : poolCache.values()) {
            try {
                ds.close();
            } catch (Exception ignored) {
            }
        }
        poolCache.clear();
    }

    /**
     * 采集 PostgreSQL 数据库实例的八项核心性能指标。
     *
     * @param instance 数据库实例地址，格式为 host:port，例如 192.168.1.100:5432
     * @param database 数据库名称，用于限定数据库级指标的采集范围
     * @return JSON 格式的八项指标数据
     */
    @Tool("采集PostgreSQL数据库实例的八项核心性能指标：活跃会话数、缓冲池命中率、锁等待会话数、每秒事务数、主从复制延迟、死元组比例、后端写入缓冲区占比、事务空闲未关闭连接数")
    public String collectDatabaseMetrics(
            @P("数据库实例地址，格式为 host:port，例如 192.168.1.100:5432") String instance,
            @P("数据库名称") String database) {

        if (instance == null || instance.isBlank()) {
            return errorJson("数据库实例地址不能为空");
        }
        if (database == null || database.isBlank()) {
            return errorJson("数据库名称不能为空");
        }

        HikariDataSource ds = getOrCreatePool(instance, database);

        try (Connection conn = ds.getConnection()) {

            ObjectNode result = OBJECT_MAPPER.createObjectNode();
            result.put("success", true);
            result.put("instance", instance);
            result.put("database", database);

            // ---- 采集八项指标 ----
            collectActiveSessions(conn, database, result);
            collectBufferHitRate(conn, database, result);
            collectLockWaitingSessions(conn, result);
            collectTransactionsPerSecond(conn, database, result);
            collectReplicationLag(conn, result);
            collectDeadTupleRatio(conn, result);
            collectBackendWriteRatio(conn, result);
            collectIdleInTransaction(conn, result);

            return OBJECT_MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            // 连接异常时清理失效的连接池
            poolCache.remove(poolKey(instance, database));
            try {
                ds.close();
            } catch (Exception ignored) {
            }
            return errorJson("指标采集失败: " + e.getMessage());
        }
    }

    // ======================== 八项指标采集方法 ========================

    /**
     * 指标一：活跃会话数（Active Sessions）。
     * <p>
     * 查询 pg_stat_activity 视图中 state = 'active' 的会话数量。
     * 该指标反映当前正在执行查询的后端进程数，数值过高可能意味着
     * 数据库负载过大或存在慢查询堆积。
     */
    private void collectActiveSessions(Connection conn, String database, ObjectNode result) {
        String sql = "SELECT count(*) AS active_sessions FROM pg_stat_activity WHERE state = 'active' AND datname = ?";
        querySingleInt(conn, sql, database, "activeSessions",
                "活跃会话数，即当前正在执行查询的后端进程数量", result);
    }

    /**
     * 指标二：缓冲池命中率（Buffer Cache Hit Rate）。
     * <p>
     * 通过 pg_stat_database 视图中的 blks_hit（缓存命中块数）
     * 与 blks_read（磁盘读取块数）计算共享缓冲区命中率：
     * <pre>
     *   hit_rate = blks_hit / (blks_hit + blks_read) × 100%
     * </pre>
     * 命中率越高说明数据更多从内存读取，磁盘 I/O 压力越小。
     * 生产环境建议该值保持在 95% 以上。
     */
    private void collectBufferHitRate(Connection conn, String database, ObjectNode result) {
        String sql =
                "SELECT blks_hit, blks_read, " +
                "  CASE WHEN (blks_hit + blks_read) > 0 " +
                "       THEN round(blks_hit * 100.0 / (blks_hit + blks_read), 2) " +
                "       ELSE 100.0 " +
                "  END AS buffer_hit_rate " +
                "FROM pg_stat_database WHERE datname = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ObjectNode node = OBJECT_MAPPER.createObjectNode();
                    node.put("description", "缓冲池命中率（百分比），反映共享缓冲区缓存效率，建议 > 95%");
                    node.put("bufferHitRate", rs.getDouble("buffer_hit_rate"));
                    node.put("blksHit", rs.getLong("blks_hit"));
                    node.put("blksRead", rs.getLong("blks_read"));
                    result.set("bufferHitRate", node);
                }
            }
        } catch (Exception e) {
            result.set("bufferHitRate", metricErrorNode("缓冲池命中率", e.getMessage()));
        }
    }

    /**
     * 指标三：锁等待会话数（Lock Waiting Sessions）。
     * <p>
     * 查询 pg_stat_activity 中 wait_event_type = 'Lock' 的会话数。
     * 这些会话因无法获取表级锁或行级锁而处于等待状态，
     * 数值持续大于 0 说明存在锁竞争，可能导致业务阻塞。
     */
    private void collectLockWaitingSessions(Connection conn, ObjectNode result) {
        String sql =
                "SELECT count(*) AS lock_waiting_sessions " +
                "FROM pg_stat_activity " +
                "WHERE wait_event_type = 'Lock'";

        querySingleInt(conn, null, sql, "lockWaitingSessions",
                "锁等待会话数，即因无法获取锁而阻塞的会话数量，大于 0 需关注", result);
    }

    /**
     * 指标四：每秒事务数（Transactions Per Second, TPS）。
     * <p>
     * 从 pg_stat_database 中获取 xact_commit（已提交事务数）、
     * xact_rollback（已回滚事务数）以及 stats_reset（统计重置时间），
     * 计算自上次统计重置以来的平均 TPS：
     * <pre>
     *   avg_tps = (xact_commit + xact_rollback) / seconds_since_reset
     * </pre>
     * 注意：这是自 stats_reset 以来的长期平均值，非瞬时 TPS。
     */
    private void collectTransactionsPerSecond(Connection conn, String database, ObjectNode result) {
        String sql =
                "SELECT xact_commit, xact_rollback, stats_reset, " +
                "  CASE WHEN extract(epoch FROM (now() - stats_reset)) > 0 " +
                "       THEN round((xact_commit + xact_rollback)::numeric / " +
                "                   extract(epoch FROM (now() - stats_reset)), 2) " +
                "       ELSE 0 " +
                "  END AS avg_tps " +
                "FROM pg_stat_database WHERE datname = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ObjectNode node = OBJECT_MAPPER.createObjectNode();
                    node.put("description", "自统计重置以来的平均每秒事务数（非瞬时TPS）");
                    node.put("avgTps", rs.getDouble("avg_tps"));
                    node.put("xactCommit", rs.getLong("xact_commit"));
                    node.put("xactRollback", rs.getLong("xact_rollback"));
                    node.put("statsReset", rs.getTimestamp("stats_reset").toString());
                    result.set("transactionsPerSecond", node);
                }
            }
        } catch (Exception e) {
            result.set("transactionsPerSecond", metricErrorNode("每秒事务数", e.getMessage()));
        }
    }

    /**
     * 指标五：主从复制延迟（Replication Lag）。
     * <p>
     * 通过 pg_is_in_recovery() 判断当前实例是主库还是从库：
     * <ul>
     *   <li>主库（primary）：查询 pg_stat_replication 视图，
     *       使用 pg_wal_lsn_diff() 计算每个从库的 WAL 延迟字节数；</li>
     *   <li>从库（standby）：通过 pg_last_xact_replay_timestamp()
     *       计算从库重放延迟秒数。</li>
     * </ul>
     */
    private void collectReplicationLag(Connection conn, ObjectNode result) {
        try {
            // 判断当前实例是主库还是从库
            boolean isInRecovery;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT pg_is_in_recovery()")) {
                rs.next();
                isInRecovery = rs.getBoolean(1);
            }

            ObjectNode node = OBJECT_MAPPER.createObjectNode();

            if (isInRecovery) {
                // ---- 从库：计算重放延迟 ----
                String sql =
                        "SELECT " +
                        "  COALESCE(extract(epoch FROM (now() - pg_last_xact_replay_timestamp())), 0) " +
                        "    AS replay_lag_seconds";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    if (rs.next()) {
                        double lagSeconds = rs.getDouble("replay_lag_seconds");
                        node.put("role", "standby");
                        node.put("description", "从库事务重放延迟（秒），即从库落后主库的时间");
                        node.put("replayLagSeconds", lagSeconds);
                    }
                }
            } else {
                // ---- 主库：查询所有从库的复制延迟 ----
                node.put("role", "primary");
                node.put("description", "主库视角下每个从库的 WAL 复制延迟（字节）");
                String sql =
                        "SELECT usename, application_name, client_addr, state, sync_state, " +
                        "  COALESCE(pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn), 0) " +
                        "    AS lag_bytes " +
                        "FROM pg_stat_replication";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    ArrayNode replicas = OBJECT_MAPPER.createArrayNode();
                    while (rs.next()) {
                        ObjectNode replica = OBJECT_MAPPER.createObjectNode();
                        replica.put("userName", rs.getString("usename"));
                        replica.put("applicationName", rs.getString("application_name"));
                        replica.put("clientAddr", rs.getString("client_addr"));
                        replica.put("state", rs.getString("state"));
                        replica.put("syncState", rs.getString("sync_state"));
                        replica.put("lagBytes", rs.getLong("lag_bytes"));
                        replicas.add(replica);
                    }
                    node.set("replicas", replicas);
                    // 如果没有任何从库连接，说明当前是单节点
                    if (replicas.size() == 0) {
                        node.put("note", "当前实例无连接的从库（单节点或从库已断开）");
                    }
                }
            }

            result.set("replicationLag", node);
        } catch (Exception e) {
            result.set("replicationLag", metricErrorNode("主从复制延迟", e.getMessage()));
        }
    }

    /**
     * 指标六：死元组比例（Dead Tuple Ratio）。
     * <p>
     * 查询 pg_stat_user_tables 视图，统计所有用户表的 n_dead_tup
     * （死元组数）与 n_live_tup（活元组数），计算整体死元组比例：
     * <pre>
     *   dead_ratio = n_dead_tup / (n_live_tup + n_dead_tup) × 100%
     * </pre>
     * 死元组比例过高说明 VACUUM 回收不及时，可能导致表膨胀和查询性能下降。
     * 建议该值控制在 10% 以下。
     */
    private void collectDeadTupleRatio(Connection conn, ObjectNode result) {
        // 汇总整体死元组比例
        String overallSql =
                "SELECT sum(n_live_tup) AS total_live_tuples, " +
                "       sum(n_dead_tup) AS total_dead_tuples, " +
                "  CASE WHEN sum(n_live_tup) + sum(n_dead_tup) > 0 " +
                "       THEN round(sum(n_dead_tup) * 100.0 / " +
                "                   (sum(n_live_tup) + sum(n_dead_tup)), 2) " +
                "       ELSE 0 " +
                "  END AS overall_dead_ratio " +
                "FROM pg_stat_user_tables";

        // 按表列出死元组比例 TOP 10，便于定位问题表
        String perTableSql =
                "SELECT schemaname || '.' || relname AS table_name, " +
                "       n_live_tup, n_dead_tup, " +
                "  CASE WHEN n_live_tup + n_dead_tup > 0 " +
                "       THEN round(n_dead_tup * 100.0 / (n_live_tup + n_dead_tup), 2) " +
                "       ELSE 0 " +
                "  END AS dead_ratio " +
                "FROM pg_stat_user_tables " +
                "ORDER BY n_dead_tup DESC LIMIT 10";

        try {
            ObjectNode node = OBJECT_MAPPER.createObjectNode();
            node.put("description", "死元组比例（百分比），反映 VACUUM 回收效率，建议 < 10%");

            // 整体汇总
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(overallSql)) {
                if (rs.next()) {
                    node.put("overallDeadRatio", rs.getDouble("overall_dead_ratio"));
                    node.put("totalLiveTuples", rs.getLong("total_live_tuples"));
                    node.put("totalDeadTuples", rs.getLong("total_dead_tuples"));
                }
            }

            // 各表详情（TOP 10）
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(perTableSql)) {
                ArrayNode tables = OBJECT_MAPPER.createArrayNode();
                while (rs.next()) {
                    ObjectNode table = OBJECT_MAPPER.createObjectNode();
                    table.put("tableName", rs.getString("table_name"));
                    table.put("liveTuples", rs.getLong("n_live_tup"));
                    table.put("deadTuples", rs.getLong("n_dead_tup"));
                    table.put("deadRatio", rs.getDouble("dead_ratio"));
                    tables.add(table);
                }
                node.set("top10Tables", tables);
            }

            result.set("deadTupleRatio", node);
        } catch (Exception e) {
            result.set("deadTupleRatio", metricErrorNode("死元组比例", e.getMessage()));
        }
    }

    /**
     * 指标七：后端写入缓冲区占比（Backend Write Buffer Ratio）。
     * <p>
     * 通过 pg_stat_bgwriter 视图，计算后端进程直接写入磁盘的缓冲区
     * 占所有缓冲区写入（后端写入 + 后台写入器写入 + 检查点写入）的比例：
     * <pre>
     *   backend_ratio = buffers_backend / (buffers_backend + buffers_clean + buffers_checkpoint) × 100%
     * </pre>
     * 该比例过高说明 PostgreSQL 的后台写入器（bgwriter）未能有效吸收写入压力，
     * 后端进程被迫自己执行写入操作，会增加查询响应延迟。
     * 建议该值控制在 10% 以下。
     */
    private void collectBackendWriteRatio(Connection conn, ObjectNode result) {
        String sql =
                "SELECT buffers_backend, buffers_clean, buffers_checkpoint, " +
                "       buffers_backend_fsync, " +
                "  CASE WHEN (buffers_backend + buffers_clean + buffers_checkpoint) > 0 " +
                "       THEN round(buffers_backend * 100.0 / " +
                "                   (buffers_backend + buffers_clean + buffers_checkpoint), 2) " +
                "       ELSE 0 " +
                "  END AS backend_write_ratio " +
                "FROM pg_stat_bgwriter";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                ObjectNode node = OBJECT_MAPPER.createObjectNode();
                node.put("description", "后端进程直接写入缓冲区占比（百分比），反映 bgwriter 写入效率，建议 < 10%");
                node.put("backendWriteRatio", rs.getDouble("backend_write_ratio"));
                node.put("buffersBackend", rs.getLong("buffers_backend"));
                node.put("buffersClean", rs.getLong("buffers_clean"));
                node.put("buffersCheckpoint", rs.getLong("buffers_checkpoint"));
                node.put("buffersBackendFsync", rs.getLong("buffers_backend_fsync"));
                result.set("backendWriteRatio", node);
            }
        } catch (Exception e) {
            result.set("backendWriteRatio", metricErrorNode("后端写入缓冲区占比", e.getMessage()));
        }
    }

    /**
     * 指标八：事务空闲未关闭连接数（Idle-in-Transaction Connections）。
     * <p>
     * 查询 pg_stat_activity 中 state = 'idle in transaction' 的会话数。
     * 这些连接已经开启事务（BEGIN）但长时间未提交或回滚，会持有锁、
     * 阻止 VACUUM 回收死元组、占用连接槽位。
     * 生产环境中该值应始终为 0。
     */
    private void collectIdleInTransaction(Connection conn, ObjectNode result) {
        String sql =
                "SELECT count(*) AS idle_in_transaction " +
                "FROM pg_stat_activity " +
                "WHERE state = 'idle in transaction'";

        querySingleInt(conn, null, sql, "idleInTransaction",
                "事务空闲未关闭连接数，即已开启事务但长时间未提交/回滚的连接，应始终为 0", result);
    }

    // ======================== 辅助方法 ========================

    /**
     * 执行返回单行单列整数值的查询。
     *
     * @param conn          数据库连接
     * @param param         如果非 null，使用 PreparedStatement 绑定该参数；否则使用 Statement
     * @param sql           SQL 语句
     * @param metricKey     结果 JSON 中的 key
     * @param description   指标的中文描述
     * @param result        输出到的 JSON 节点
     */
    private void querySingleInt(Connection conn, String param, String sql,
                                String metricKey, String description, ObjectNode result) {
        try {
            long value;
            if (param != null) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, param);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        value = rs.getLong(1);
                    }
                }
            } else {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    rs.next();
                    value = rs.getLong(1);
                }
            }
            ObjectNode node = OBJECT_MAPPER.createObjectNode();
            node.put("description", description);
            node.put(metricKey, value);
            result.set(metricKey, node);
        } catch (Exception e) {
            result.set(metricKey, metricErrorNode(description, e.getMessage()));
        }
    }

    // ======================== 连接池管理 ========================

    /**
     * 获取或创建指定实例/数据库的连接池。
     * 每条连接池最多保留 2 个连接，空闲 5 分钟回收，最大存活 10 分钟。
     */
    private HikariDataSource getOrCreatePool(String instance, String database) {
        String key = poolKey(instance, database);
        return poolCache.computeIfAbsent(key, k -> createPool(instance, database));
    }

    /**
     * 创建新的 HikariCP 连接池。
     * <p>
     * 使用 application.yml 中 spring.datasource.username / password 作为认证凭据。
     */
    private HikariDataSource createPool(String instance, String database) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + instance + "/" + database);
        config.setUsername(dbUsername);
        config.setPassword(dbPassword);
        config.setDriverClassName("org.postgresql.Driver");
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(2);
        config.setIdleTimeout(300_000);
        config.setMaxLifetime(600_000);
        config.setConnectionTimeout(10_000);
        return new HikariDataSource(config);
    }

    private static String poolKey(String instance, String database) {
        return instance + "/" + database;
    }

    // ======================== JSON 构建工具 ========================

    /**
     * 构建采集失败的顶层 JSON。
     */
    private String errorJson(String message) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("success", false);
        root.put("error", message);
        return root.toString();
    }

    /**
     * 构建单项指标采集失败时的错误节点。
     */
    private ObjectNode metricErrorNode(String metricName, String errorMessage) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("error", metricName + " 采集失败: " + errorMessage);
        return node;
    }
}
