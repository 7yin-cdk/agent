package com.library.agent.healthcheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.agent.config.HealthCheckProperties.Target;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据库健康指标采集器。
 * <p>
 * 对单个目标实例建立短连接，采集一组与异常判定相关的关键指标
 * （活跃会话数、缓冲池命中率、锁等待会话数、死元组比例、后端写入占比、
 * 事务空闲未关闭连接数、复制延迟），供 LLM 巡检工具与规则兜底共用。
 */
@Component
public class DatabaseMetricsCollector {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${spring.datasource.username}")
    private String fallbackUsername;

    @Value("${spring.datasource.password}")
    private String fallbackPassword;

    /**
     * 采集指定实例的健康指标。
     *
     * @param target 巡检目标实例配置
     * @return 指标 Map，键为指标名，值为数值或错误说明；失败时返回仅含 error 的 Map
     */
    public Map<String, Object> collect(Target target) {
        String url = "jdbc:postgresql://" + target.getHost() + ":" + target.getPort() + "/" + target.getDatabase();
        String username = isBlank(target.getUsername()) ? fallbackUsername : target.getUsername();
        String password = isBlank(target.getPassword()) ? fallbackPassword : target.getPassword();

        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("activeSessions", queryActiveSessions(conn, target.getDatabase()));
            metrics.put("bufferHitRate", queryBufferHitRate(conn, target.getDatabase()));
            metrics.put("lockWaitingSessions", querySingleInt(conn, "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"));
            metrics.put("idleInTransaction", querySingleInt(conn, "SELECT count(*) FROM pg_stat_activity WHERE state = 'idle in transaction'"));
            metrics.put("deadTupleRatio", queryDeadTupleRatio(conn));
            metrics.put("backendWriteRatio", queryBackendWriteRatio(conn));
            collectReplicationLag(conn, metrics);
            return metrics;
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "指标采集失败: " + e.getMessage());
            return error;
        }
    }

    /**
     * 序列化为 JSON 字符串，供 @Tool 返回给大模型。
     */
    public String toJson(Target target, Map<String, Object> metrics) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("instance", target.getName());
        root.put("host", target.getHost());
        root.put("port", target.getPort());
        root.put("database", target.getDatabase());
        root.putPOJO("metrics", metrics);
        return root.toString();
    }

    /**
     * 查询指定数据库的活跃会话数。
     */
    private long queryActiveSessions(Connection conn, String database) {
        String sql = "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND datname = ?";
        return querySingleLong(conn, sql, database);
    }

    /**
     * 查询指定数据库的缓冲池命中率（百分比）。
     */
    private double queryBufferHitRate(Connection conn, String database) {
        String sql = "SELECT round(blks_hit * 100.0 / NULLIF(blks_hit + blks_read, 0), 2) FROM pg_stat_database WHERE datname = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 查询整体死元组比例（百分比）。
     */
    private double queryDeadTupleRatio(Connection conn) {
        String sql = "SELECT round(sum(n_dead_tup) * 100.0 / NULLIF(sum(n_live_tup) + sum(n_dead_tup), 0), 2) FROM pg_stat_user_tables";
        return querySingleDouble(conn, sql);
    }

    /**
     * 查询后端写入缓冲区占比（百分比）。
     */
    private double queryBackendWriteRatio(Connection conn) {
        String sql = "SELECT round(buffers_backend * 100.0 / NULLIF(buffers_backend + buffers_clean + buffers_checkpoint, 0), 2) FROM pg_stat_bgwriter";
        return querySingleDouble(conn, sql);
    }

    /**
     * 采集复制延迟：主库记录各从库 WAL 延迟字节数，从库记录重放延迟秒数。
     */
    private void collectReplicationLag(Connection conn, Map<String, Object> metrics) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT pg_is_in_recovery()")) {
            rs.next();
            boolean isInRecovery = rs.getBoolean(1);
            if (isInRecovery) {
                try (Statement s = conn.createStatement();
                     ResultSet r = s.executeQuery("SELECT COALESCE(extract(epoch FROM (now() - pg_last_xact_replay_timestamp())), 0)")) {
                    r.next();
                    metrics.put("replicationRole", "standby");
                    metrics.put("replicationLagSeconds", r.getDouble(1));
                }
            } else {
                try (Statement s = conn.createStatement();
                     ResultSet r = s.executeQuery("SELECT COALESCE(sum(pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)), 0) FROM pg_stat_replication")) {
                    r.next();
                    metrics.put("replicationRole", "primary");
                    metrics.put("replicationLagBytes", r.getLong(1));
                }
            }
        } catch (Exception e) {
            metrics.put("replicationError", e.getMessage());
        }
    }

    /**
     * 执行返回单行单列整数的查询。
     */
    private long querySingleInt(Connection conn, String sql) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 执行带一个参数的返回单行单列整数查询。
     */
    private long querySingleLong(Connection conn, String sql, String param) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 执行返回单行单列浮点数的查询。
     */
    private double querySingleDouble(Connection conn, String sql) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getDouble(1);
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
