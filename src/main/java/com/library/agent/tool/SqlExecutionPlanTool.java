package com.library.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class SqlExecutionPlanTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    @Tool("获取指定SQL语句在数据库中的优化器执行计划，支持estimated（估算计划）和actual（实际执行）两种模式，返回结构化JSON供Agent解读或进一步分析（如发现全表扫描、错误连接顺序等）")
    public String getSqlExecutionPlan(
            @P("数据库实例地址，格式为host:port，例如：localhost:5432") String instance,
            @P("数据库名称") String database,
            @P("需要获取执行计划的SQL语句，仅支持单条SELECT/INSERT/UPDATE/DELETE语句") String sql,
            @P("执行计划模式：estimated（估算计划，不实际执行SQL）或 actual（实际执行SQL并收集真实统计信息），默认为estimated") String mode) {

        if (mode == null || mode.isBlank()) {
            mode = "estimated";
        }

        if (!"estimated".equals(mode) && !"actual".equals(mode)) {
            return errorJson("不支持的执行计划模式: " + mode + "，可选值为 estimated 或 actual");
        }

        String sanitized = sanitizeSql(sql);
        if (sanitized == null) {
            return errorJson("SQL语句不合法：仅支持单条SELECT、INSERT、UPDATE、DELETE语句");
        }

        if ("actual".equals(mode) && !isReadOnly(sanitized)) {
            return errorJson("actual 模式仅支持 SELECT 语句，INSERT/UPDATE/DELETE 请使用 estimated 模式");
        }

        String poolKey = instance + "/" + database;
        HikariDataSource ds = poolCache.computeIfAbsent(poolKey, k -> createPool(instance, database));
        String explainSql = buildExplainSql(sanitized, mode);

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(explainSql)) {

            List<JsonNode> planNodes = new ArrayList<>();
            while (rs.next()) {
                JsonNode parsed = OBJECT_MAPPER.readTree(rs.getString(1));
                if (parsed.isArray()) {
                    for (JsonNode node : parsed) {
                        planNodes.add(node);
                    }
                } else {
                    planNodes.add(parsed);
                }
            }

            ObjectNode analysis = analyzePlan(planNodes);

            ObjectNode result = OBJECT_MAPPER.createObjectNode();
            result.put("success", true);
            result.put("instance", instance);
            result.put("database", database);
            result.put("mode", mode);
            result.put("sql", sanitized);

            ArrayNode planArray = OBJECT_MAPPER.createArrayNode();
            for (JsonNode node : planNodes) {
                planArray.add(node);
            }
            result.set("plan", planArray);
            result.set("analysis", analysis);

            return OBJECT_MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            poolCache.remove(poolKey);
            try {
                ds.close();
            } catch (Exception ignored) {
            }
            return errorJson("获取执行计划失败: " + e.getMessage());
        }
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
        config.addDataSourceProperty("socketTimeout", "20");
        return new HikariDataSource(config);
    }

    private String buildExplainSql(String sql, String mode) {
        if ("actual".equals(mode)) {
            return "EXPLAIN (ANALYZE, COSTS, VERBOSE, BUFFERS, FORMAT JSON) " + sql;
        }
        return "EXPLAIN (COSTS, VERBOSE, BUFFERS, FORMAT JSON) " + sql;
    }

    private String sanitizeSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        String trimmed = sql.trim().replaceAll(";+$", "").trim();
        String upper = trimmed.toUpperCase().replaceAll("\\s+", " ");
        if (!upper.startsWith("SELECT")
                && !upper.startsWith("INSERT")
                && !upper.startsWith("UPDATE")
                && !upper.startsWith("DELETE")
                && !upper.startsWith("WITH")) {
            return null;
        }
        return trimmed;
    }

    private boolean isReadOnly(String sql) {
        String upper = sql.toUpperCase().replaceAll("\\s+", " ").trim();
        return upper.startsWith("SELECT") || upper.startsWith("WITH");
    }

    private ObjectNode analyzePlan(List<JsonNode> planNodes) {
        Set<String> scanTypes = new HashSet<>();
        Set<String> joinTypes = new HashSet<>();
        List<String> warnings = new ArrayList<>();
        double totalCost = 0.0;

        for (JsonNode node : planNodes) {
            collectPlanInfo(node, scanTypes, joinTypes, warnings);
            if (node.has("Plan")) {
                collectPlanInfo(node.get("Plan"), scanTypes, joinTypes, warnings);
            }
        }

        for (JsonNode node : planNodes) {
            JsonNode plan = node.has("Plan") ? node.get("Plan") : node;
            totalCost += plan.path("Total Cost").asDouble(0.0);
        }

        ObjectNode analysis = OBJECT_MAPPER.createObjectNode();
        analysis.put("totalCost", Math.round(totalCost * 100.0) / 100.0);

        ArrayNode scanArray = OBJECT_MAPPER.createArrayNode();
        for (String s : scanTypes) {
            scanArray.add(s);
        }
        analysis.set("scanNodes", scanArray);

        ArrayNode joinArray = OBJECT_MAPPER.createArrayNode();
        for (String j : joinTypes) {
            joinArray.add(j);
        }
        analysis.set("joinNodes", joinArray);

        ArrayNode warnArray = OBJECT_MAPPER.createArrayNode();
        for (String w : warnings) {
            warnArray.add(w);
        }
        analysis.set("warnings", warnArray);

        return analysis;
    }

    private void collectPlanInfo(JsonNode node, Set<String> scanTypes,
                                  Set<String> joinTypes, List<String> warnings) {
        if (node == null || !node.isObject()) {
            return;
        }

        String nodeType = node.path("Node Type").asText("");

        if (nodeType.contains("Scan")) {
            String relation = node.path("Relation Name").asText("");
            String alias = node.path("Alias").asText("");
            String label = nodeType;
            if (!relation.isEmpty()) {
                label += " on " + relation;
            } else if (!alias.isEmpty()) {
                label += " on " + alias;
            }
            scanTypes.add(label);

            if ("Seq Scan".equals(nodeType)) {
                double rows = node.path("Plan Rows").asDouble(0);
                String filter = node.path("Filter").asText("");
                String warning = "全表扫描(Seq Scan): " + (relation.isEmpty() ? alias : relation);
                if (!filter.isEmpty()) {
                    warning += "，Filter: " + filter;
                }
                warning += "，预估行数: " + rows;
                warnings.add(warning);
            }
        }

        if (nodeType.contains("Join")) {
            String joinLabel = nodeType;
            String joinType = node.path("Join Type").asText("");
            if (!joinType.isEmpty()) {
                joinLabel += " (" + joinType + ")";
            }
            joinTypes.add(joinLabel);

            if ("Nested Loop".equals(nodeType)) {
                double rows = node.path("Plan Rows").asDouble(0);
                if (rows > 10000) {
                    warnings.add("嵌套循环连接(Nested Loop Join)，预估行数: " + rows + "，大数据量下可能性能较差");
                }
            }
        }

        JsonNode plans = node.get("Plans");
        if (plans != null && plans.isArray()) {
            for (JsonNode child : plans) {
                collectPlanInfo(child, scanTypes, joinTypes, warnings);
            }
        }
    }

    private String errorJson(String message) {
        ObjectNode error = OBJECT_MAPPER.createObjectNode();
        error.put("success", false);
        error.put("error", message);
        return error.toString();
    }
}
