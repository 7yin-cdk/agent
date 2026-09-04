package com.library.agent.enums;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Agent 第二层任务上下文的注册表（唯一事实源）。
 * <p>
 * 每个枚举常量对应一个可被路由选中的任务能力模块：{@link #routeName} 是路由输出值，
 * 也是 classpath 下 Prompt/task/{routeName}.md 的文件主名；{@link #description} 是面向
 * 用户/模型的中文能力简介，用于纠错提示与"无匹配能力"回退文案的可用清单。
 * <p>
 * 注意：路由校验、纠错重试、回退文案与上下文加载路径全部由此类派生，不再信任 LLM 输出。
 * 新增任务时必须同步维护三处：{@code resources/Prompt/RoutePrompt.md} 的路由元数据、
 * {@code resources/Prompt/task/{routeName}.md} 的上下文文件、以及本枚举。
 */
public enum AgentTask {

    WEATHER_QUERY("weather_query", "查询城市实时天气，支持温度/风力/湿度/降雨等天气状况"),
    SQL_EXECUTION_PLAN("sql_execution_plan", "获取并分析 SQL 执行计划(EXPLAIN)，识别全表扫描/索引使用/连接方式与性能瓶颈"),
    DATABASE_METRICS("database_metrics", "采集数据库八项核心性能指标，用于健康巡检与性能诊断"),
    SLOW_QUERY("slow_query", "基于 pg_stat_statements 查询与解读 Top 10 慢查询并给出优化建议");

    private final String routeName;
    private final String description;

    AgentTask(String routeName, String description) {
        this.routeName = routeName;
        this.description = description;
    }

    /**
     * 路由输出值，同时是 Prompt/task/{routeName}.md 的文件主名。
     */
    public String routeName() {
        return routeName;
    }

    /**
     * 中文能力简介。
     */
    public String description() {
        return description;
    }

    /**
     * 第二层任务上下文在 classpath 下的资源路径。
     */
    public String resourcePath() {
        return "Prompt/task/" + routeName + ".md";
    }

    /**
     * 校验并解析合法任务名；空串、前后空格或未注册的名字一律返回空。
     *
     * @param rawName LLM 路由输出的原始任务名
     * @return 命中的任务；非法时为空
     */
    public static Optional<AgentTask> fromRouteName(String rawName) {
        if (rawName == null) {
            return Optional.empty();
        }
        String name = rawName.trim();
        for (AgentTask task : values()) {
            if (task.routeName.equals(name)) {
                return Optional.of(task);
            }
        }
        return Optional.empty();
    }

    /**
     * 空格分隔的合法任务名清单，用于纠错重试提示中的硬约束列举。
     */
    public static String routeNamesText() {
        StringBuilder sb = new StringBuilder();
        for (AgentTask task : values()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(task.routeName);
        }
        return sb.toString();
    }

    /**
     * 逐行渲染可用能力清单（含中文简介），用于"无匹配能力"回退文案。
     */
    public static List<String> availableTaskLines() {
        List<String> lines = new ArrayList<>();
        for (AgentTask task : values()) {
            lines.add("- " + task.routeName + "：" + task.description);
        }
        return lines;
    }

    /**
     * 可用能力清单的多行文本形式，供回退文案直接拼装。
     */
    public static String availableTasksText() {
        StringBuilder sb = new StringBuilder();
        for (AgentTask task : values()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(task.routeName).append("：").append(task.description);
        }
        return sb.toString();
    }
}
