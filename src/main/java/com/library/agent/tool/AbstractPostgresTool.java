package com.library.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.agent.tool.retry.DbRetryProperties;
import com.library.agent.tool.retry.SqlRetryExecutor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * PostgreSQL 工具基类，承载各工具共用的连接池缓存、凭据注入与 JSON 组装能力。
 * <p>
 * 设计说明：
 * <ul>
 *   <li>本类不标注 {@code @Component}，也不含 {@code @Tool} 方法，因此不会被
 *       {@code ToolCallingServiceImpl.registerTools} 的工具扫描逻辑注册；</li>
 *   <li>连接池按 "instance/database" 为键缓存，同一实例的多次调用复用同一池；</li>
 *   <li>凭据统一取自 {@code spring.datasource.username/password}。巡检配置
 *       （{@code agent.healthcheck.targets}）中的 target 级账密暂未生效，因为池键
 *       不含账号，混用两套账密会出现"先建的池赢"的非确定性行为，需单独跟进。</li>
 *   <li>子类统一经 {@link #executeWithRetry} 访问数据库：建池与取连接都在 try 内，
 *       瞬时故障由重试内核自愈。子类的单项/单区块 catch 必须先调用
 *       {@code SqlRetryClassifier.rethrowIfConnectionLevel}，否则连接中途断掉时
 *       内层会把异常吞成局部错误节点，重试永远不触发。</li>
 * </ul>
 *
 * @author 郑钦
 */
public abstract class AbstractPostgresTool {

    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 连接池缓存，key = "instance/database"，复用同一实例的连接池。
     */
    protected final ConcurrentMap<String, HikariDataSource> poolCache = new ConcurrentHashMap<>();

    @Value("${spring.datasource.username}")
    protected String dbUsername;

    @Value("${spring.datasource.password}")
    protected String dbPassword;

    @Autowired
    protected DbRetryProperties retryProperties;

    /**
     * 一次数据库工作单元，入参为已借出的连接。
     * <p>
     * 实现方须保证幂等：瞬时故障会重跑整个工作单元，重复执行必须收敛到同一终态。
     */
    @FunctionalInterface
    protected interface SqlWork {

        /**
         * @param conn 已借出的数据库连接，由模板方法负责关闭
         * @return 工具返回给大模型的 JSON 字符串
         * @throws Exception 任何失败，连接级故障由重试内核重试
         */
        String run(Connection conn) throws Exception;
    }

    /**
     * 连接提供者。把取连接抽成可覆写方法，是测试注入假连接的唯一接缝。
     */
    @FunctionalInterface
    protected interface ConnectionSupplier {

        /**
         * @return 可用的数据库连接
         * @throws Exception 建池或取连接失败
         */
        Connection get() throws Exception;
    }

    /**
     * 容器关闭时释放所有连接池。子类不应重复实现该方法。
     */
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
     * 获取或创建指定实例/数据库的连接池。
     * 每条连接池最多保留 2 个连接，空闲 5 分钟回收，最大存活 10 分钟。
     * <p>
     * 取连接最多等待 5 秒：健康连接通常在百毫秒内建立，5 秒已是足够余量，
     * 而它决定了"连接超时/池耗尽"这两类可重试故障的单次代价，进而决定重试预算是否够用。
     */
    protected HikariDataSource getOrCreatePool(String instance, String database) {
        String key = poolKey(instance, database);
        return poolCache.computeIfAbsent(key, k -> createPool(instance, database));
    }

    /**
     * 创建新的 HikariCP 连接池。
     * <p>
     * 使用 application.yml 中 spring.datasource.username / password 作为认证凭据。
     */
    protected HikariDataSource createPool(String instance, String database) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + instance + "/" + database);
        config.setUsername(dbUsername);
        config.setPassword(dbPassword);
        config.setDriverClassName("org.postgresql.Driver");
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(2);
        config.setIdleTimeout(300_000);
        config.setMaxLifetime(600_000);
        config.setConnectionTimeout(5_000);
        config.addDataSourceProperty("socketTimeout", "20");
        return new HikariDataSource(config);
    }

    /**
     * 取连接的唯一入口；测试覆写本方法即可注入会抛异常的假连接。
     *
     * @param instance 数据库实例地址 host:port
     * @param database 数据库名称
     * @return 连接提供者
     */
    protected ConnectionSupplier connectionSupplier(String instance, String database) {
        return () -> getOrCreatePool(instance, database).getConnection();
    }

    /**
     * 数据库访问模板：建池与取连接都在 try 内，瞬时故障由重试内核自愈，
     * 重试耗尽或遇到确定性故障时才降级为 success=false 的 JSON。
     * <p>
     * 注意 try-with-resources 的 {@code conn.close()} 也在工作单元内：死连接上关闭失败
     * 会被算作一次可重试故障。Hikari 的 ProxyConnection.close 会吞掉底层错误，
     * 这里属于理论边界，不做特殊处理。
     *
     * @param instance       数据库实例地址 host:port，仅用于连接池键与清理
     * @param database       数据库名称，仅用于连接池键与清理
     * @param failureMessage 重试耗尽后的错误前缀，如 "指标采集失败"
     * @param work           幂等的数据库工作单元
     * @return 工作单元的结果；失败时返回结构化错误 JSON
     */
    protected String executeWithRetry(String instance, String database,
                                      String failureMessage, SqlWork work) {
        SqlRetryExecutor executor = new SqlRetryExecutor(retryProperties);
        try {
            return executor.execute("db-tool",
                    () -> {
                        try (Connection conn = connectionSupplier(instance, database).get()) {
                            return work.run(conn);
                        }
                    },
                    () -> evictPool(instance, database));
        } catch (Exception e) {
            evictPool(instance, database);
            return errorJson(failureMessage + ": " + e.getMessage());
        }
    }

    /**
     * 连接池缓存键：instance/database。
     */
    protected static String poolKey(String instance, String database) {
        return instance + "/" + database;
    }

    /**
     * 连接异常时清理已失效的连接池，避免后续调用持续复用坏连接。
     * <p>
     * 缓存的池可能早于本次调用被换掉（并发下另一个调用已重建），因此按池键取出实际
     * 缓存的数据源再关闭，而不是关闭调用方持有的引用。
     *
     * @param instance 数据库实例地址
     * @param database 数据库名称
     */
    protected void evictPool(String instance, String database) {
        HikariDataSource ds = poolCache.remove(poolKey(instance, database));
        if (ds == null) {
            return;
        }
        try {
            ds.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 构建成功结果的基础节点，统一携带 success/instance/database 三键。
     * <p>
     * 键的插入顺序即最终 JSON 的字段顺序，调用方追加业务字段时应接在之后。
     *
     * @param instance 实例标识（业务名或 host:port）
     * @param database 数据库名称
     * @return 已填充三键的 JSON 对象节点
     */
    protected ObjectNode baseResult(String instance, String database) {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("success", true);
        result.put("instance", instance);
        result.put("database", database);
        return result;
    }

    /**
     * 构建采集失败的顶层 JSON。
     */
    protected String errorJson(String message) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("success", false);
        root.put("error", message);
        return root.toString();
    }

    /**
     * 构建单项数据获取失败时的局部错误节点，使单个子查询失败不影响整体结果。
     */
    protected ObjectNode errorNode(String message) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("error", message);
        return node;
    }

    /**
     * 读取可空数值列并写入 JSON 节点，null 时写 JSON null。
     * <p>
     * 用于 pg_stat_replication 的 *_lag、pg_stat_user_tables 的比率等确实可能为 null
     * 的列；直接 {@code rs.getDouble} 会把 null 静默变成 0，丢失"无数据"语义。
     */
    protected void putNumberOrNull(ObjectNode node, String key, ResultSet rs, String column) {
        try {
            java.math.BigDecimal value = rs.getBigDecimal(column);
            if (value == null) {
                node.putNull(key);
            } else {
                node.put(key, value);
            }
        } catch (Exception e) {
            node.putNull(key);
        }
    }

    /**
     * 读取可空时间列并写入 JSON 节点，null 时写 JSON null。
     * <p>
     * {@code pg_stat_user_tables.last_vacuum / last_autovacuum / last_analyze} 在从未
     * 触发过对应动作时为 null，直接 toString 会抛 NPE，故统一走本方法。
     *
     * @param node  目标 JSON 对象
     * @param key   写入的键名
     * @param rs    当前结果集行
     * @param column 数据库列名
     */
    protected void putTimestampOrNull(ObjectNode node, String key, ResultSet rs, String column) {
        try {
            java.sql.Timestamp ts = rs.getTimestamp(column);
            if (ts == null) {
                node.putNull(key);
            } else {
                node.put(key, ts.toString());
            }
        } catch (Exception e) {
            node.putNull(key);
        }
    }
}
