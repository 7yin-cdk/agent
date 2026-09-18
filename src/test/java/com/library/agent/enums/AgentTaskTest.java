package com.library.agent.enums;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentTask 注册表单测（离线）。
 * <p>
 * 守卫注册表与 classpath 资源、RoutePrompt.md 模板三者的一致性：每个枚举对应的
 * 第二层任务上下文文件必须存在，路由名必须无重复且可被 fromRouteName 解析。
 */
class AgentTaskTest {

    @Test
    void everyTaskResourceExists() {
        for (AgentTask task : AgentTask.values()) {
            assertTrue(new ClassPathResource(task.resourcePath()).exists(),
                    "任务上下文资源不存在: " + task.resourcePath());
        }
    }

    @Test
    void routeNamesAreUnique() {
        Set<String> names = new HashSet<>();
        for (AgentTask task : AgentTask.values()) {
            assertTrue(names.add(task.routeName()), "重复的任务名: " + task.routeName());
        }
    }

    @Test
    void fromRouteNameResolvesRegisteredNames() {
        for (AgentTask task : AgentTask.values()) {
            assertEquals(task, AgentTask.fromRouteName(task.routeName()).orElse(null),
                    "应命中: " + task.routeName());
            assertEquals(task, AgentTask.fromRouteName("  " + task.routeName() + "  ").orElse(null),
                    "应容忍首尾空格: " + task.routeName());
        }
    }

    @Test
    void fromRouteNameRejectsUnknownOrBlank() {
        assertTrue(AgentTask.fromRouteName(null).isEmpty());
        assertTrue(AgentTask.fromRouteName("").isEmpty());
        assertTrue(AgentTask.fromRouteName("   ").isEmpty());
        assertTrue(AgentTask.fromRouteName("code_generation").isEmpty());
        assertTrue(AgentTask.fromRouteName("weather_qry").isEmpty());
    }

    @Test
    void routePromptMentionsEveryTaskName() throws Exception {
        /* 防静态路由模板与注册表漂移：新增任务而漏更 RoutePrompt.md 时本测试即红 */
        String routePrompt = new String(new ClassPathResource("Prompt/RoutePrompt.md")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        for (AgentTask task : AgentTask.values()) {
            assertTrue(routePrompt.contains(task.routeName()),
                    "RoutePrompt.md 缺少任务名: " + task.routeName());
        }
    }

    @Test
    void routePromptDocumentsNoMatchSentinel() throws Exception {
        /* 哨兵值定义在枚举、使用在路由服务、声明在路由模板，三者漂移即红 */
        String routePrompt = new String(new ClassPathResource("Prompt/RoutePrompt.md")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(routePrompt.contains(AgentTask.NO_MATCH),
                "RoutePrompt.md 缺少无匹配哨兵值: " + AgentTask.NO_MATCH);
    }

    @Test
    void noMatchSentinelIsNotATaskName() {
        assertTrue(AgentTask.fromRouteName(AgentTask.NO_MATCH).isEmpty(),
                "哨兵值不是任务能力，不应被解析成任务");
        assertTrue(AgentTask.isNoMatch("  no_match  "), "应容忍大小写与首尾空格");
        assertTrue(AgentTask.isNoMatch(AgentTask.NO_MATCH));
        assertFalse(AgentTask.isNoMatch("slow_query"));
        assertFalse(AgentTask.isNoMatch(null));
        assertFalse(AgentTask.isNoMatch("   "));
        assertFalse(AgentTask.availableTasksText().contains(AgentTask.NO_MATCH),
                "哨兵值不应出现在面向用户的可用能力清单中");
    }

    @Test
    void availableTasksTextListsAllTasks() {
        String text = AgentTask.availableTasksText();
        for (AgentTask task : AgentTask.values()) {
            assertTrue(text.contains("- " + task.routeName() + "："), "能力清单缺少: " + task.routeName());
        }
    }
}
