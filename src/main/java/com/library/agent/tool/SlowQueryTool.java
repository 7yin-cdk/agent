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
 * PostgreSQL 慢查询采集工具。
 * <p>
 * 基于 pg_stat_statements 扩展，按平均执行时间排序获取 Top 10 慢查询，
 * 包含查询文本、执行次数、各维度耗时、缓冲区命中率等关键信息。
 */
@Component
public class SlowQueryTool {

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
     * 查询数据库中平均执行时间最长的 Top 10 慢查询。
     * <p>
     * 数据来源为 pg_stat_statements 扩展视图，
     * 如果目标数据库未启用该扩展，将返回错误提示。
     *
     * @param instance 数据库实例地址，格式为 host:port
     * @param database 数据库名称
     * @return JSON 格式的 Top 10 慢查询列表
     */
    @Tool("查询数据库中平均执行时间最长的Top 10慢查询，基于pg_stat_statements扩展，返回查询文本、执行次数、各维度耗时及缓冲区命中率等关键信息")
    public String getTopSlowQueries(
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

            // 检查 pg_stat_statements 扩展是否已安装
            if (!checkExtension(conn)) {
                return errorJson("pg_stat_statements 扩展未安装或未启用，"
                        + "请在目标数据库中执行: CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
            }

            // 查询 Top 10 慢查询，按平均执行时间降序
            String sql =
                    "SELECT queryid, " +
                    "       query, " +
                    "       calls, " +
                    "       round(mean_exec_time::numeric, 2) AS mean_exec_time_ms, " +
                    "       round(total_exec_time::numeric, 2) AS total_exec_time_ms, " +
                    "       round(max_exec_time::numeric, 2) AS max_exec_time_ms, " +
                    "       round(min_exec_time::numeric, 2) AS min_exec_time_ms, " +
                    "       round(stddev_exec_time::numeric, 2) AS stddev_exec_time_ms, " +
                    "       rows, " +
                    "       shared_blks_hit, " +
                    "       shared_blks_read, " +
                    "       CASE WHEN (shared_blks_hit + shared_blks_read) > 0 " +
                    "            THEN round(shared_blks_hit * 100.0 / " +
                    "                        (shared_blks_hit + shared_blks_read), 2) " +
                    "            ELSE 100.0 " +
                    "       END AS buffer_hit_pct " +
                    "FROM pg_stat_statements " +
                    "WHERE query !~* 'pg_stat_statements|pg_stat_activity|pg_stat_replication' " +
                    "ORDER BY mean_exec_time DESC " +
                    "LIMIT 10";

            ArrayNode slowQueries = OBJECT_MAPPER.createArrayNode();

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                while (rs.next()) {
                    ObjectNode query = OBJECT_MAPPER.createObjectNode();

                    // 查询基本信息
                    query.put("queryId", rs.getLong("queryid"));
                    // 截取查询文本前 2000 个字符，避免超长 SQL 撑大返回体
                    String queryText = rs.getString("query");
                    query.put("query",
                            queryText.length() > 2000
                                    ? queryText.substring(0, 2000) + "...(truncated)"
                                    : queryText);

                    // 执行频率统计
                    query.put("calls", rs.getLong("calls"));

                    // 执行时间统计（毫秒）
                    query.put("meanExecTimeMs", rs.getDouble("mean_exec_time_ms"));
                    query.put("totalExecTimeMs", rs.getDouble("total_exec_time_ms"));
                    query.put("maxExecTimeMs", rs.getDouble("max_exec_time_ms"));
                    query.put("minExecTimeMs", rs.getDouble("min_exec_time_ms"));
                    query.put("stddevExecTimeMs", rs.getDouble("stddev_exec_time_ms"));

                    // 影响行数（累计返回/影响的总行数）
                    query.put("rows", rs.getLong("rows"));

                    // 共享缓冲区命中统计
                    query.put("sharedBlksHit", rs.getLong("shared_blks_hit"));
                    query.put("sharedBlksRead", rs.getLong("shared_blks_read"));
                    query.put("bufferHitPct", rs.getDouble("buffer_hit_pct"));

                    slowQueries.add(query);
                }
            }

            ObjectNode result = OBJECT_MAPPER.createObjectNode();
            result.put("success", true);
            result.put("instance", instance);
            result.put("database", database);
            result.put("totalSlowQueries", slowQueries.size());
            result.set("slowQueries", slowQueries);

            return OBJECT_MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            // 连接异常时清理失效的连接池
            poolCache.remove(poolKey(instance, database));
            try {
                ds.close();
            } catch (Exception ignored) {
            }
            return errorJson("慢查询采集失败: " + e.getMessage());
        }
    }

    /**
     * 重置 pg_stat_statements 统计数据。
     * <p>
     * 调用后将清空所有历史查询统计，重新开始计数。
     * 通常在性能基线变更或清理测试数据后使用。
     *
     * @param instance 数据库实例地址
     * @param database 数据库名称
     * @return 操作结果
     */
    @Tool("重置pg_stat_statements统计数据，清空所有历史慢查询记录，重新开始统计")
    public String resetSlowQueryStats(
            @P("数据库实例地址，格式为 host:port") String instance,
            @P("数据库名称") String database) {

        if (instance == null || instance.isBlank()) {
            return errorJson("数据库实例地址不能为空");
        }
        if (database == null || database.isBlank()) {
            return errorJson("数据库名称不能为空");
        }

        HikariDataSource ds = getOrCreatePool(instance, database);

        try (Connection conn = ds.getConnection()) {
            if (!checkExtension(conn)) {
                return errorJson("pg_stat_statements 扩展未安装或未启用");
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT pg_stat_statements_reset()");
            }

            ObjectNode result = OBJECT_MAPPER.createObjectNode();
            result.put("success", true);
            result.put("instance", instance);
            result.put("database", database);
            result.put("message", "pg_stat_statements 统计已重置");

            return OBJECT_MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            poolCache.remove(poolKey(instance, database));
            try {
                ds.close();
            } catch (Exception ignored) {
            }
            return errorJson("重置统计数据失败: " + e.getMessage());
        }
    }

    // ======================== 辅助方法 ========================

    /**
     * 检查 pg_stat_statements 扩展是否已安装。
     * <p>
     * 通过查询 pg_extension 系统表判断。
     */
    private boolean checkExtension(Connection conn) {
        String sql = "SELECT count(*) FROM pg_extension WHERE extname = 'pg_stat_statements'";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ======================== 连接池管理 ========================

    private HikariDataSource getOrCreatePool(String instance, String database) {
        String key = poolKey(instance, database);
        return poolCache.computeIfAbsent(key, k -> createPool(instance, database));
    }

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

    private String errorJson(String message) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("success", false);
        root.put("error", message);
        return root.toString();
    }
}
