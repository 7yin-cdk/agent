package com.library.agent.tool;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.agent.tool.retry.SqlRetryClassifier;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 锁阻塞链下钻工具。
 * <p>
 * 指标层发现"锁等待会话数不为零"、会话层发现 wait_event_type 为 Lock 之后，
 * 本工具给出"谁在等、被谁挡住、挡住多久、持什么锁、挡住几个人"。
 * <p>
 * 返回的是扁平的阻塞边列表（每条边 = 一个 blocker/waiter 对），
 * 不递归组装成树：链的语义由大模型依据 pid 自行串联，递归 CTE 收益不足。
 *
 * @author 郑钦
 */
@Component
@RequiredArgsConstructor
public class LockBlockingTool extends AbstractPostgresTool {

    private final InstanceResolver instanceResolver;

    /**
     * 获取当前数据库的锁阻塞边列表。
     * <p>
     * 数据来源为 {@code pg_blocking_pids(pid)}：对每个正在等待锁的会话展开出阻塞者。
     * 阻塞者侧额外关联 {@code pg_locks}，优先取普通堆表（relkind='r'）上的已授予锁，
     * 避免把索引锁当成被争用的对象；{@code rootBlocker} 表示该阻塞者自身未在等锁，
     * 是整条链的源头。
     *
     * @param instance 数据库实例地址 host:port，或巡检配置中的业务名
     * @param database 数据库名称；使用业务名时可省略
     * @return 阻塞边列表 JSON；无阻塞时返回空数组
     */
    @Tool("获取指定数据库当前的锁阻塞关系（阻塞方 pid/用户/应用/状态/事务已开启时长/backend_xmin/正在执行的 SQL、持有锁的模式与对象、是否链源头，以及等待方 pid/用户/状态/已等待时长/正在执行的 SQL、被该阻塞方挡住的会话数），用于定位锁等待的根因会话与长事务")
    public String getBlockingChains(
            @P("数据库实例地址 host:port，或巡检配置中的业务名（如 rag库）") String instance,
            @P(required = false, value = "数据库名称；使用业务名时可省略，默认取该实例配置的库名") String database) {

        InstanceResolver.ResolvedTarget target = instanceResolver.resolve(instance, database);
        if (target == null) {
            return errorJson("无法解析数据库实例: " + instance
                    + "。请提供 host:port 形式的地址（如 localhost:5432），或 agent.healthcheck.targets "
                    + "中已定义的业务名（如 rag库）；仅当使用业务名时可省略 database 参数");
        }

        return executeWithRetry(target.hostPort(), target.database(), "锁阻塞链采集失败", conn -> {

            ObjectNode result = baseResult(instance, target.database());
            result.put("resolvedInstance", target.hostPort());

            String sql =
                    "WITH blocking AS ( "
                            + "  SELECT w.pid AS waiter_pid, b.pid AS blocker_pid "
                            + "  FROM pg_stat_activity w "
                            + "  CROSS JOIN LATERAL unnest(pg_blocking_pids(w.pid)) AS b(pid) "
                            + "  WHERE w.datname = ? "
                            + ") "
                            + "SELECT bl.pid AS blocker_pid, bl.usename AS blocker_user, "
                            + "       bl.application_name AS blocker_app, bl.state AS blocker_state, "
                            + "       round(extract(epoch from (now()-bl.xact_start))::numeric, 2) AS blocker_xact_seconds, "
                            + "       bl.backend_xmin::text AS blocker_xmin, left(bl.query, 200) AS blocker_query, "
                            + "       lk.mode AS blocker_lock_mode, lk.relation_name AS blocker_lock_relation, "
                            + "       lk.granted AS blocker_lock_granted, "
                            + "       w.pid AS waiter_pid, w.usename AS waiter_user, w.state AS waiter_state, "
                            + "       round(extract(epoch from (now()-w.query_start))::numeric, 2) AS waiter_query_seconds, "
                            + "       left(w.query, 200) AS waiter_query, "
                            + "       (SELECT count(*) FROM blocking bb WHERE bb.blocker_pid = bl.pid) AS blocked_waiter_count, "
                            + "       NOT EXISTS (SELECT 1 FROM pg_stat_activity x WHERE x.pid = bl.pid "
                            + "                   AND x.wait_event_type = 'Lock') AS is_root_blocker "
                            + "FROM blocking bk "
                            + "JOIN pg_stat_activity bl ON bl.pid = bk.blocker_pid "
                            + "JOIN pg_stat_activity w ON w.pid = bk.waiter_pid "
                            + "LEFT JOIN LATERAL ( "
                            + "  SELECT l.mode, "
                            + "         CASE WHEN l.relation <> 0 THEN l.relation::regclass::text END AS relation_name, "
                            + "         l.granted "
                            + "  FROM pg_locks l "
                            + "  LEFT JOIN pg_class c ON c.oid = l.relation "
                            + "  WHERE l.pid = bl.pid AND l.granted "
                            + "  ORDER BY (COALESCE(c.relkind, '') <> 'r'), (l.relation IS NULL) "
                            + "  LIMIT 1 "
                            + ") lk ON true "
                            + "ORDER BY bl.xact_start ASC NULLS LAST";

            ArrayNode edges = OBJECT_MAPPER.createArrayNode();

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, target.database());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ObjectNode edge = OBJECT_MAPPER.createObjectNode();
                        edge.put("blockerPid", rs.getLong("blocker_pid"));
                        edge.put("blockerUser", rs.getString("blocker_user"));
                        edge.put("blockerApplicationName", rs.getString("blocker_app"));
                        edge.put("blockerState", rs.getString("blocker_state"));
                        putNumberOrNull(edge, "blockerXactSeconds", rs, "blocker_xact_seconds");
                        edge.put("blockerBackendXmin", rs.getString("blocker_xmin"));
                        edge.put("blockerQuery", rs.getString("blocker_query"));
                        edge.put("blockerLockMode", rs.getString("blocker_lock_mode"));
                        edge.put("blockerLockRelation", rs.getString("blocker_lock_relation"));
                        boolean lockGranted = rs.getBoolean("blocker_lock_granted");
                        if (rs.wasNull()) {
                            edge.putNull("blockerLockGranted");
                        } else {
                            edge.put("blockerLockGranted", lockGranted);
                        }

                        edge.put("waiterPid", rs.getLong("waiter_pid"));
                        edge.put("waiterUser", rs.getString("waiter_user"));
                        edge.put("waiterState", rs.getString("waiter_state"));
                        putNumberOrNull(edge, "waiterQuerySeconds", rs, "waiter_query_seconds");
                        edge.put("waiterQuery", rs.getString("waiter_query"));

                        edge.put("blockedWaiterCount", rs.getLong("blocked_waiter_count"));
                        edge.put("rootBlocker", rs.getBoolean("is_root_blocker"));
                        edges.add(edge);
                    }
                }
            } catch (Exception e) {
                SqlRetryClassifier.rethrowIfConnectionLevel(e);
                result.put("blockingChainsError", e.getMessage());
            }

            result.put("edgeCount", edges.size());
            result.set("blockingChains", edges);

            return OBJECT_MAPPER.writeValueAsString(result);
        });
    }
}
