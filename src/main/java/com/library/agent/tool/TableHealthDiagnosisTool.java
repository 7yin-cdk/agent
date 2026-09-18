package com.library.agent.tool;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.agent.tool.retry.SqlRetryClassifier;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 表膨胀、VACUUM 与表访问下钻工具。
 * <p>
 * 指标层发现"死元组比例超阈值""索引使用率偏低""表体积异常增长"之后，本工具负责区分
 * 三种互斥的根因：(a) 自动清理会追上，无需干预；(b) 长事务或旧 xmin 钉住了清理水位；
 * (c) 表太大或写入太快，清理跟不上。为此在死元组明细之外，额外并行给出
 * "持有最旧 xmin 的会话"与"运行最久的事务"两组独立证据。
 * <p>
 * 注：所有 pg_stat_* 计数器都会被 {@code pg_stat_reset()}（或
 * {@code pg_stat_reset_single_table_counters()}）清零，解读 seq_scan/idx_scan
 * 时需结合统计重置时间。
 *
 * @author 郑钦
 */
@Component
@RequiredArgsConstructor
public class TableHealthDiagnosisTool extends AbstractPostgresTool {

    private final InstanceResolver instanceResolver;

    /**
     * 获取死元组与 VACUUM 状态，附带卡住清理水位的事务证据。
     * <p>
     * 主列表按死元组绝对条数降序而非死元组比例降序：比例会让只有两三行的小表
     * （如评测运行表）以极高比例挤掉真正需要清理的大表，绝对值更能反映清理收益。
     *
     * @param instance 数据库实例地址 host:port，或巡检配置中的业务名
     * @param database 数据库名称；使用业务名时可省略
     * @return 死元组明细、最旧 xmin 持有者、最长事务的 JSON
     */
    @Tool("获取指定数据库中死元组最多的表明细（表名、活/死元组数、死元组比例、最近 vacuum/autovacuum/analyze 时间、autovacuum 次数、表体积），并附带持有最旧 backend_xmin 的会话与运行时间最长的事务，用于判断表膨胀是由长事务/旧快照卡住清理水位、还是写入量超过 autovacuum 清理能力，或自动清理即将追上")
    public String getVacuumAndBloatStatus(
            @P("数据库实例地址 host:port，或巡检配置中的业务名（如 rag库）") String instance,
            @P(required = false, value = "数据库名称；使用业务名时可省略，默认取该实例配置的库名") String database) {

        InstanceResolver.ResolvedTarget target = instanceResolver.resolve(instance, database);
        if (target == null) {
            return resolveError(instance);
        }

        return executeWithRetry(target.hostPort(), target.database(), "表膨胀状态采集失败", conn -> {

            ObjectNode result = baseResult(instance, target.database());
            result.put("resolvedInstance", target.hostPort());

            collectDeadTuples(conn, target.database(), result);
            collectOldestXminHolder(conn, result);
            collectLongestTransactions(conn, result);

            return OBJECT_MAPPER.writeValueAsString(result);
        });
    }

    /**
     * 获取表的访问方式统计与热表上的未使用索引。
     * <p>
     * 两张子表口径保持一致的"热表"定义（seq_scan 降序、其次活元组数降序），
     * 便于把"这张表在被全表扫描"与"这张表的这个索引从未被用过"对应起来。
     *
     * @param instance 数据库实例地址 host:port，或巡检配置中的业务名
     * @param database 数据库名称；使用业务名时可省略
     * @return 表访问统计与未使用索引的 JSON
     */
    @Tool("获取指定数据库中被顺序扫描最多的表（表名、顺序扫描次数与读取元组数、索引扫描次数与回表元组数、增删改计数、活元组数、表体积、索引使用率百分比），以及这些热表上扫描次数为 0 的未使用索引（索引名、扫描次数、索引体积），用于定位缺失索引或冗余索引")
    public String getTableAccessStats(
            @P("数据库实例地址 host:port，或巡检配置中的业务名（如 rag库）") String instance,
            @P(required = false, value = "数据库名称；使用业务名时可省略，默认取该实例配置的库名") String database) {

        InstanceResolver.ResolvedTarget target = instanceResolver.resolve(instance, database);
        if (target == null) {
            return resolveError(instance);
        }

        return executeWithRetry(target.hostPort(), target.database(), "表访问统计采集失败", conn -> {

            ObjectNode result = baseResult(instance, target.database());
            result.put("resolvedInstance", target.hostPort());

            collectTableAccess(conn, result);
            collectUnusedIndexes(conn, result);

            return OBJECT_MAPPER.writeValueAsString(result);
        });
    }

    /**
     * 死元组最多的 Top 10 表。
     */
    private void collectDeadTuples(Connection conn, String database, ObjectNode result) {
        String sql =
                "SELECT format('%I.%I', schemaname, relname) AS table_name, n_live_tup, n_dead_tup, "
                        + "       round(n_dead_tup * 100.0 / GREATEST(n_live_tup + n_dead_tup, 1), 2) AS dead_ratio, "
                        + "       last_vacuum, last_autovacuum, last_analyze, autovacuum_count, "
                        + "       pg_relation_size(relid)::bigint AS table_bytes, "
                        + "       pg_total_relation_size(relid)::bigint AS total_bytes "
                        + "FROM pg_stat_user_tables "
                        + "WHERE n_dead_tup > 0 "
                        + "ORDER BY n_dead_tup DESC LIMIT 10";

        ArrayNode tables = OBJECT_MAPPER.createArrayNode();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ObjectNode table = OBJECT_MAPPER.createObjectNode();
                table.put("tableName", rs.getString("table_name"));
                table.put("liveTuples", rs.getLong("n_live_tup"));
                table.put("deadTuples", rs.getLong("n_dead_tup"));
                putNumberOrNull(table, "deadRatioPct", rs, "dead_ratio");
                putTimestampOrNull(table, "lastVacuum", rs, "last_vacuum");
                putTimestampOrNull(table, "lastAutovacuum", rs, "last_autovacuum");
                putTimestampOrNull(table, "lastAnalyze", rs, "last_analyze");
                table.put("autovacuumCount", rs.getLong("autovacuum_count"));
                table.put("tableBytes", rs.getLong("table_bytes"));
                table.put("totalBytes", rs.getLong("total_bytes"));
                tables.add(table);
            }
        } catch (Exception e) {
            SqlRetryClassifier.rethrowIfConnectionLevel(e);
            result.put("deadTupleTablesError", e.getMessage());
        }

        result.put("bloatedTableCount", tables.size());
        result.set("bloatedTables", tables);
    }

    /**
     * 持有最旧 backend_xmin 的会话。
     * <p>
     * backend_xmin 仅对超级用户或 pg_monitor 角色可见，权限不足时查询返回空集，
     * 此处降级为 note 而非报错；判断是否有长事务仍可依据 longestRunningTransactions。
     */
    private void collectOldestXminHolder(Connection conn, ObjectNode result) {
        String sql =
                "SELECT pid, usename, state, application_name, backend_xmin::text AS backend_xmin, "
                        + "       age(backend_xmin) AS xmin_age, "
                        + "       round(extract(epoch from (now()-xact_start))::numeric, 2) AS xact_seconds, "
                        + "       left(query, 200) AS query_text "
                        + "FROM pg_stat_activity "
                        + "WHERE backend_xmin IS NOT NULL AND pid <> pg_backend_pid() "
                        + "ORDER BY age(backend_xmin) DESC LIMIT 5";

        ArrayNode holders = OBJECT_MAPPER.createArrayNode();
        try {
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode holder = OBJECT_MAPPER.createObjectNode();
                    holder.put("pid", rs.getLong("pid"));
                    holder.put("user", rs.getString("usename"));
                    holder.put("state", rs.getString("state"));
                    holder.put("applicationName", rs.getString("application_name"));
                    holder.put("backendXmin", rs.getString("backend_xmin"));
                    putNumberOrNull(holder, "xminAge", rs, "xmin_age");
                    putNumberOrNull(holder, "xactSeconds", rs, "xact_seconds");
                    holder.put("query", rs.getString("query_text"));
                    holders.add(holder);
                }
            }
        } catch (Exception e) {
            SqlRetryClassifier.rethrowIfConnectionLevel(e);
            result.put("oldestXminHolderError", e.getMessage());
        }

        result.set("oldestXminHolder", holders);
        if (holders.isEmpty()) {
            result.put("oldestXminHolderNote", "未查到持有 backend_xmin 的会话：可能当前没有活跃事务快照，"
                    + "也可能当前账号不是超级用户且不具备 pg_monitor 角色，无权查看其他会话的 backend_xmin");
        }
    }

    /**
     * 运行时间最长的 Top 5 事务，是不受权限限制的兜底证据。
     */
    private void collectLongestTransactions(Connection conn, ObjectNode result) {
        String sql =
                "SELECT pid, usename, state, application_name, "
                        + "       round(extract(epoch from (now()-xact_start))::numeric, 2) AS xact_seconds, "
                        + "       round(extract(epoch from (now()-query_start))::numeric, 2) AS query_seconds, "
                        + "       left(query, 200) AS query_text "
                        + "FROM pg_stat_activity "
                        + "WHERE xact_start IS NOT NULL AND pid <> pg_backend_pid() "
                        + "ORDER BY xact_start ASC LIMIT 5";

        ArrayNode transactions = OBJECT_MAPPER.createArrayNode();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ObjectNode transaction = OBJECT_MAPPER.createObjectNode();
                transaction.put("pid", rs.getLong("pid"));
                transaction.put("user", rs.getString("usename"));
                transaction.put("state", rs.getString("state"));
                transaction.put("applicationName", rs.getString("application_name"));
                putNumberOrNull(transaction, "xactSeconds", rs, "xact_seconds");
                putNumberOrNull(transaction, "querySeconds", rs, "query_seconds");
                transaction.put("query", rs.getString("query_text"));
                transactions.add(transaction);
            }
        } catch (Exception e) {
            SqlRetryClassifier.rethrowIfConnectionLevel(e);
            result.put("longestRunningTransactionsError", e.getMessage());
        }

        result.set("longestRunningTransactions", transactions);
    }

    /**
     * 顺序扫描最多的 Top 10 表及其索引使用率。
     */
    private void collectTableAccess(Connection conn, ObjectNode result) {
        String sql =
                "SELECT format('%I.%I', schemaname, relname) AS table_name, "
                        + "       COALESCE(seq_scan, 0) AS seq_scan, COALESCE(seq_tup_read, 0) AS seq_tup_read, "
                        + "       COALESCE(idx_scan, 0) AS idx_scan, COALESCE(idx_tup_fetch, 0) AS idx_tup_fetch, "
                        + "       n_tup_ins, n_tup_upd, n_tup_del, n_live_tup, "
                        + "       pg_relation_size(relid)::bigint AS table_bytes, "
                        + "       pg_total_relation_size(relid)::bigint AS total_bytes, "
                        + "       CASE WHEN COALESCE(seq_scan, 0) + COALESCE(idx_scan, 0) > 0 "
                        + "            THEN round(COALESCE(idx_scan, 0) * 100.0 / (COALESCE(seq_scan, 0) + COALESCE(idx_scan, 0)), 2) "
                        + "       END AS index_usage_pct "
                        + "FROM pg_stat_user_tables "
                        + "ORDER BY COALESCE(seq_scan, 0) DESC, n_live_tup DESC LIMIT 10";

        ArrayNode tables = OBJECT_MAPPER.createArrayNode();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ObjectNode table = OBJECT_MAPPER.createObjectNode();
                table.put("tableName", rs.getString("table_name"));
                table.put("seqScan", rs.getLong("seq_scan"));
                table.put("seqTupRead", rs.getLong("seq_tup_read"));
                table.put("idxScan", rs.getLong("idx_scan"));
                table.put("idxTupFetch", rs.getLong("idx_tup_fetch"));
                table.put("insertedTuples", rs.getLong("n_tup_ins"));
                table.put("updatedTuples", rs.getLong("n_tup_upd"));
                table.put("deletedTuples", rs.getLong("n_tup_del"));
                table.put("liveTuples", rs.getLong("n_live_tup"));
                table.put("tableBytes", rs.getLong("table_bytes"));
                table.put("totalBytes", rs.getLong("total_bytes"));
                putNumberOrNull(table, "indexUsagePct", rs, "index_usage_pct");
                tables.add(table);
            }
        } catch (Exception e) {
            SqlRetryClassifier.rethrowIfConnectionLevel(e);
            result.put("tableAccessError", e.getMessage());
        }

        result.put("hotTableCount", tables.size());
        result.set("hotTables", tables);
    }

    /**
     * 上述热表上从未被扫描过的索引。
     */
    private void collectUnusedIndexes(Connection conn, ObjectNode result) {
        String sql =
                "WITH hot AS ( "
                        + "  SELECT schemaname, relname FROM pg_stat_user_tables "
                        + "  ORDER BY COALESCE(seq_scan, 0) DESC, n_live_tup DESC LIMIT 10 "
                        + ") "
                        + "SELECT format('%I.%I', i.schemaname, i.relname) AS table_name, i.indexrelname AS index_name, "
                        + "       i.idx_scan, pg_relation_size(i.indexrelid)::bigint AS index_bytes "
                        + "FROM pg_stat_user_indexes i "
                        + "JOIN hot ON hot.schemaname = i.schemaname AND hot.relname = i.relname "
                        + "ORDER BY i.idx_scan ASC, pg_relation_size(i.indexrelid) DESC LIMIT 20";

        ArrayNode indexes = OBJECT_MAPPER.createArrayNode();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ObjectNode index = OBJECT_MAPPER.createObjectNode();
                index.put("tableName", rs.getString("table_name"));
                index.put("indexName", rs.getString("index_name"));
                index.put("idxScan", rs.getLong("idx_scan"));
                index.put("indexBytes", rs.getLong("index_bytes"));
                indexes.add(index);
            }
        } catch (Exception e) {
            SqlRetryClassifier.rethrowIfConnectionLevel(e);
            result.put("unusedIndexesError", e.getMessage());
        }

        result.set("unusedIndexes", indexes);
    }

    /**
     * 统一的实例解析失败提示。
     */
    private String resolveError(String instance) {
        return errorJson("无法解析数据库实例: " + instance
                + "。请提供 host:port 形式的地址（如 localhost:5432），或 agent.healthcheck.targets "
                + "中已定义的业务名（如 rag库）；仅当使用业务名时可省略 database 参数");
    }
}
