package com.library.agent.tool;

import com.library.agent.config.HealthCheckProperties;
import com.library.agent.config.HealthCheckProperties.Target;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 实例参数解析器，把 Agent 传入的 instance/database 归一为可直连的 host:port 与库名。
 * <p>
 * 支持两种写法：
 * <ul>
 *   <li>巡检配置中的业务名（如 "rag库"）：在 {@code agent.healthcheck.targets} 中按 name
 *       命中后，拼出 host:port，并可在 database 缺省时回填该 target 配置的库名；</li>
 *   <li>字面地址 host:port（如 "localhost:5432"）：原样透传，无冒号时补默认端口 5432。</li>
 * </ul>
 * 已知问题：不引入 target 级账密。连接池键是解析后的 "hostPort/database"，若同一台库
 * 既被业务名也被字面地址命中，两套账密会出现"先建的池赢"的非确定性行为，故统一沿用
 * {@code spring.datasource.*}，target 级账密需单独跟进。
 *
 * @author 郑钦
 */
@Component
@RequiredArgsConstructor
public class InstanceResolver {

    /**
     * 字面地址缺省端口。
     */
    private static final int DEFAULT_PORT = 5432;

    private final HealthCheckProperties properties;

    /**
     * 解析后的连接目标。
     *
     * @param hostPort 可直接用于 JDBC URL 的 host:port
     * @param database 数据库名称
     */
    public record ResolvedTarget(String hostPort, String database) {
    }

    /**
     * 将业务名或字面地址解析为连接目标。
     *
     * @param instance 业务名或 host:port
     * @param database 数据库名称，为空时业务名场景回填配置中的库名
     * @return 解析结果；无法解析时返回 null
     */
    public ResolvedTarget resolve(String instance, String database) {
        if (instance == null || instance.isBlank()) {
            return null;
        }

        String trimmed = instance.trim();
        Target target = findTarget(trimmed);
        if (target != null) {
            if (target.getHost() == null || target.getHost().isBlank()) {
                return null;
            }
            int port = target.getPort() == null ? DEFAULT_PORT : target.getPort();
            String db = isBlank(database) ? target.getDatabase() : database.trim();
            if (isBlank(db)) {
                return null;
            }
            return new ResolvedTarget(target.getHost().trim() + ":" + port, db);
        }

        String hostPort = trimmed.contains(":") ? trimmed : trimmed + ":" + DEFAULT_PORT;
        if (isBlank(database)) {
            return null;
        }
        return new ResolvedTarget(hostPort, database.trim());
    }

    /**
     * 按业务名查找巡检目标实例，与 DatabaseHealthCheckTool 的取值口径保持一致。
     */
    private Target findTarget(String instanceName) {
        if (properties.getTargets() == null) {
            return null;
        }
        for (Target target : properties.getTargets()) {
            if (target.getName() != null && target.getName().equals(instanceName)) {
                return target;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
