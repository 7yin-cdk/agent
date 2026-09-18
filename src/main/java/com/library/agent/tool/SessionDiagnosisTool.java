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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 活跃会话与等待事件下钻工具。
 * <p>
 * 指标层发现"活跃会话数偏高""锁等待会话数不为零"之后，本工具负责把
 * "有几个会话"落到"是哪些会话、卡在什么等待事件上"，为后续选择锁阻塞、
 * 慢查询、复制延迟等下钻方向提供依据。
 * <p>
 * 本工具只返回观测数据，不返回任何路由建议或处置结论。
 *
 * @author 郑钦
 */
@Component
@RequiredArgsConstructor
public class SessionDiagnosisTool extends AbstractPostgresTool {

    /**
     * 等待事件明细中每个大类的等待事件条数上限，避免未聚合的原始分布撑大返回体。
     */
    private static final int MAX_WAIT_EVENTS_PER_TYPE = 10;

    private final InstanceResolver instanceResolver;

    /**
     * 列出当前数据库的非空闲客户端会话明细及状态汇总。
     * <p>
     * 过滤口径：限定目标库、排除本工具自身连接、仅 client backend、排除 idle 状态。
     * 同时给出 backend_xmin 与 age(backend_xmin)，用于判断是否存在阻塞 VACUUM 回收的旧事务。
     *
     * @param instance 数据库实例地址 host:port，或巡检配置中的业务名
     * @param database 数据库名称；使用业务名时可省略
     * @return 会话明细与状态计数的 JSON
     */
    @Tool("列出指定数据库当前所有非空闲的客户端会话明细（pid、用户、应用、客户端地址、状态、等待事件、backend_xmin 及其年龄、查询已执行时长、事务已开启时长、状态停留时长、SQL 前 200 字符）并按状态汇总计数，用于排查活跃会话异常、长事务与锁等待源头")
    public String listActiveSessions(
            @P("数据库实例地址 host:port，或巡检配置中的业务名（如 rag库）") String instance,
            @P(required = false, value = "数据库名称；使用业务名时可省略，默认取该实例配置的库名") String database) {

        InstanceResolver.ResolvedTarget target = instanceResolver.resolve(instance, database);
        if (target == null) {
            return resolveError(instance);
        }

        return executeWithRetry(target.hostPort(), target.database(), "活跃会话采集失败", conn -> {

            ObjectNode result = baseResult(instance, target.database());
            result.put("resolvedInstance", target.hostPort());

            ArrayNode sessions = OBJECT_MAPPER.createArrayNode();
            String sessionsError = null;
            String sessionsSql =
                    "SELECT pid, usename, application_name, client_addr::text AS client_addr, state, "
                            + "       wait_event_type, wait_event, "
                            + "       round(extract(epoch from (now()-query_start))::numeric, 2) AS query_seconds, "
                            + "       round(extract(epoch from (now()-xact_start))::numeric, 2) AS xact_seconds, "
                            + "       round(extract(epoch from (now()-state_change))::numeric, 2) AS state_seconds, "
                            + "       backend_xmin::text AS backend_xmin, age(backend_xmin) AS xmin_age, "
                            + "       left(query, 200) AS query_text "
                            + "FROM pg_stat_activity "
                            + "WHERE datname = ? AND pid <> pg_backend_pid() "
                            + "  AND backend_type = 'client backend' AND state <> 'idle' "
                            + "ORDER BY xact_start ASC NULLS LAST LIMIT 20";

            try (PreparedStatement ps = conn.prepareStatement(sessionsSql)) {
                ps.setString(1, target.database());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ObjectNode session = OBJECT_MAPPER.createObjectNode();
                        session.put("pid", rs.getLong("pid"));
                        session.put("user", rs.getString("usename"));
                        session.put("applicationName", rs.getString("application_name"));
                        session.put("clientAddr", rs.getString("client_addr"));
                        session.put("state", rs.getString("state"));
                        session.put("waitEventType", rs.getString("wait_event_type"));
                        session.put("waitEvent", rs.getString("wait_event"));
                        putNumberOrNull(session, "querySeconds", rs, "query_seconds");
                        putNumberOrNull(session, "xactSeconds", rs, "xact_seconds");
                        putNumberOrNull(session, "stateSeconds", rs, "state_seconds");
                        session.put("backendXmin", rs.getString("backend_xmin"));
                        putNumberOrNull(session, "xminAge", rs, "xmin_age");
                        session.put("query", rs.getString("query_text"));
                        sessions.add(session);
                    }
                }
            } catch (Exception e) {
                SqlRetryClassifier.rethrowIfConnectionLevel(e);
                sessionsError = e.getMessage();
            }

            result.put("sessionCount", sessions.size());
            result.set("activeSessions", sessions);
            if (sessionsError != null) {
                result.put("sessionsError", sessionsError);
            }

            ArrayNode stateSummary = OBJECT_MAPPER.createArrayNode();
            String stateSummaryError = null;
            String stateSql =
                    "SELECT COALESCE(state, 'unknown') AS state, count(*) AS sessions "
                            + "FROM pg_stat_activity "
                            + "WHERE datname = ? AND pid <> pg_backend_pid() "
                            + "  AND backend_type = 'client backend' "
                            + "GROUP BY 1 ORDER BY 2 DESC";

            try (PreparedStatement ps = conn.prepareStatement(stateSql)) {
                ps.setString(1, target.database());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ObjectNode item = OBJECT_MAPPER.createObjectNode();
                        item.put("state", rs.getString("state"));
                        item.put("sessions", rs.getLong("sessions"));
                        stateSummary.add(item);
                    }
                }
            } catch (Exception e) {
                SqlRetryClassifier.rethrowIfConnectionLevel(e);
                stateSummaryError = e.getMessage();
            }

            result.set("stateSummary", stateSummary);
            if (stateSummaryError != null) {
                result.put("stateSummaryError", stateSummaryError);
            }

            return OBJECT_MAPPER.writeValueAsString(result);
        });
    }

    /**
     * 按等待事件大类汇总当前会话分布。
     * <p>
     * 输出形如 {@code [{waitEventType, sessions, topWaitEvents:[{waitEvent, sessions}]}]}，
     * 并给出占用会话最多的 {@code dominantWaitEventType}。
     * 这里刻意不附带"下一步该查什么"的建议，路由映射由提示词承担。
     *
     * @param instance 数据库实例地址 host:port，或巡检配置中的业务名
     * @param database 数据库名称；使用业务名时可省略
     * @return 等待事件分布的 JSON
     */
    @Tool("按等待事件大类（wait_event_type）与具体等待事件（wait_event）统计指定数据库当前所有客户端会话的分布，返回各大类会话数、各具体等待事件的会话数及占比最大的等待事件大类，用于判断数据库整体卡在锁、IO、客户端等哪一类资源上")
    public String getWaitEventDistribution(
            @P("数据库实例地址 host:port，或巡检配置中的业务名（如 rag库）") String instance,
            @P(required = false, value = "数据库名称；使用业务名时可省略，默认取该实例配置的库名") String database) {

        InstanceResolver.ResolvedTarget target = instanceResolver.resolve(instance, database);
        if (target == null) {
            return resolveError(instance);
        }

        return executeWithRetry(target.hostPort(), target.database(), "等待事件分布采集失败", conn -> {

            ObjectNode result = baseResult(instance, target.database());
            result.put("resolvedInstance", target.hostPort());

            String sql =
                    "SELECT COALESCE(wait_event_type, 'Running/CPU') AS wait_event_type, "
                            + "       COALESCE(wait_event, '-') AS wait_event, count(*) AS sessions "
                            + "FROM pg_stat_activity "
                            + "WHERE datname = ? AND pid <> pg_backend_pid() "
                            + "  AND backend_type = 'client backend' "
                            + "GROUP BY 1, 2 ORDER BY 1, 3 DESC";

            Map<String, ObjectNode> byType = new LinkedHashMap<>();
            long totalSessions = 0;
            long dominantSessions = -1;
            String dominantWaitEventType = null;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, target.database());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String waitEventType = rs.getString("wait_event_type");
                        long sessions = rs.getLong("sessions");

                        ObjectNode typeNode = byType.get(waitEventType);
                        if (typeNode == null) {
                            typeNode = OBJECT_MAPPER.createObjectNode();
                            typeNode.put("waitEventType", waitEventType);
                            typeNode.put("sessions", 0L);
                            typeNode.set("topWaitEvents", OBJECT_MAPPER.createArrayNode());
                            byType.put(waitEventType, typeNode);
                        }

                        long accumulated = typeNode.get("sessions").asLong() + sessions;
                        typeNode.put("sessions", accumulated);
                        totalSessions += sessions;

                        ArrayNode topWaitEvents = (ArrayNode) typeNode.get("topWaitEvents");
                        if (topWaitEvents.size() < MAX_WAIT_EVENTS_PER_TYPE) {
                            ObjectNode waitNode = OBJECT_MAPPER.createObjectNode();
                            waitNode.put("waitEvent", rs.getString("wait_event"));
                            waitNode.put("sessions", sessions);
                            topWaitEvents.add(waitNode);
                        }

                        if (sessions > dominantSessions) {
                            dominantSessions = sessions;
                            dominantWaitEventType = waitEventType;
                        }
                    }
                }
            }

            ArrayNode distribution = OBJECT_MAPPER.createArrayNode();
            for (ObjectNode typeNode : byType.values()) {
                distribution.add(typeNode);
            }

            result.put("totalSessions", totalSessions);
            result.put("dominantWaitEventType", dominantWaitEventType);
            result.set("distribution", distribution);

            return OBJECT_MAPPER.writeValueAsString(result);
        });
    }

    /**
     * 统一的实例解析失败提示，同时覆盖业务名未配置与 database 缺失两种情况。
     */
    private String resolveError(String instance) {
        return errorJson("无法解析数据库实例: " + instance
                + "。请提供 host:port 形式的地址（如 localhost:5432），或 agent.healthcheck.targets "
                + "中已定义的业务名（如 rag库）；仅当使用业务名时可省略 database 参数");
    }
}
