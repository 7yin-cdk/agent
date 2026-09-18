package com.library.agent.tool;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.agent.tool.retry.SqlRetryClassifier;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * PostgreSQL 慢查询采集工具。
 * <p>
 * 基于 pg_stat_statements 扩展，按平均执行时间排序获取 Top 10 慢查询，
 * 包含查询文本、执行次数、各维度耗时、缓冲区命中率等关键信息。
 */
@Component
public class SlowQueryTool extends AbstractPostgresTool {

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

        return executeWithRetry(instance, database, "慢查询采集失败", conn -> {

            /* 检查 pg_stat_statements 扩展是否已安装；连接级失败由 checkExtension 上抛触发重试 */
            if (!checkExtension(conn)) {
                return errorJson("pg_stat_statements 扩展未安装或未启用，"
                        + "请在目标数据库中执行: CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
            }

            /* 查询 Top 10 慢查询，按平均执行时间降序 */
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

            ObjectNode result = baseResult(instance, database);
            result.put("totalSlowQueries", slowQueries.size());
            result.set("slowQueries", slowQueries);

            return OBJECT_MAPPER.writeValueAsString(result);
        });
    }

    /**
     * 重置 pg_stat_statements 统计数据。
     * <p>
     * 调用后将清空所有历史查询统计，重新开始计数。
     * 通常在性能基线变更或清理测试数据后使用。
     * <p>
     * 本方法可安全重试：{@code pg_stat_statements_reset()} 幂等，重复执行等同于执行一次，
     * 因此瞬时故障重试不会带来额外副作用。新增写工具时须先确认其幂等性再复用本模板。
     *
     * @param instance 数据库实例地址
     * @param database 数据库名称
     * @return 操作结果
     */
    @Tool("重置pg_stat_statements统计数据，清空所有历史慢查询记录，重新开始统计")
    @ToolAccess(ToolAccess.Type.WRITE)
    public String resetSlowQueryStats(
            @P("数据库实例地址，格式为 host:port") String instance,
            @P("数据库名称") String database) {

        if (instance == null || instance.isBlank()) {
            return errorJson("数据库实例地址不能为空");
        }
        if (database == null || database.isBlank()) {
            return errorJson("数据库名称不能为空");
        }

        return executeWithRetry(instance, database, "重置统计数据失败", conn -> {

            if (!checkExtension(conn)) {
                return errorJson("pg_stat_statements 扩展未安装或未启用");
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT pg_stat_statements_reset()");
            }

            ObjectNode result = baseResult(instance, database);
            result.put("message", "pg_stat_statements 统计已重置");

            return OBJECT_MAPPER.writeValueAsString(result);
        });
    }

    // ======================== 辅助方法 ========================

    /**
     * 检查 pg_stat_statements 扩展是否已安装。
     * <p>
     * 通过查询 pg_extension 系统表判断。连接级失败必须先上抛：否则死连接会被静默判成
     * "扩展未安装"，把大模型引向创建扩展这一完全错误的方向。
     */
    private boolean checkExtension(Connection conn) {
        String sql = "SELECT count(*) FROM pg_extension WHERE extname = 'pg_stat_statements'";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1) > 0;
        } catch (Exception e) {
            SqlRetryClassifier.rethrowIfConnectionLevel(e);
            return false;
        }
    }

}
