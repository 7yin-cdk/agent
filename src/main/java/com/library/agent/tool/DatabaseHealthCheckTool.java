package com.library.agent.tool;

import com.library.agent.config.HealthCheckProperties;
import com.library.agent.config.HealthCheckProperties.Target;
import com.library.agent.healthcheck.DatabaseMetricsCollector;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 数据库健康巡检工具。
 * <p>
 * 供大模型在 ReAct 循环中调用：按业务名解析目标实例并返回实时指标 JSON，
 * 由大模型根据阈值自行判断是否异常。指标采集与阈值判定相分离，
 * 该工具只负责"拿真实数据"，不做结论。
 */
@Component
@RequiredArgsConstructor
public class DatabaseHealthCheckTool {

    private final HealthCheckProperties properties;
    private final DatabaseMetricsCollector collector;

    /**
     * 获取指定数据库实例的实时健康指标。
     *
     * @param instanceName 数据库实例业务名，例如：rag库
     * @return 指标 JSON，包含活跃会话数、缓冲池命中率、锁等待会话数、死元组比例、
     *         后端写入占比、事务空闲未关闭连接数、复制延迟等原始数值
     */
    @Tool("获取指定数据库实例的实时健康指标，返回各指标原始数值，供判断数据库是否异常。注意：该工具只返回数据，不包含异常判定结论")
    public String checkDatabaseHealth(
            @P("数据库实例业务名，在巡检配置中定义，例如：rag库") String instanceName) {

        Target target = findTarget(instanceName);
        if (target == null) {
            return "{\"error\":\"未找到数据库实例：" + instanceName
                    + "，请在 agent.healthcheck.targets 配置中定义\"}";
        }

        Map<String, Object> metrics = collector.collect(target);
        return collector.toJson(target, metrics);
    }

    /**
     * 按业务名查找巡检目标实例。
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
}
