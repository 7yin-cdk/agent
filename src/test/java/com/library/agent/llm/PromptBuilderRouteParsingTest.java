package com.library.agent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PromptBuilder.extractRouteTask 语法解析单测（离线）。
 * <p>
 * 校验路由 LLM 各种脏输出都不抛异常：裸 JSON、Markdown 围栏、纯文本等，
 * 并确保只对"task 字段非空文本"返回非空结果。
 */
class PromptBuilderRouteParsingTest {

    @Test
    void parsesPlainJson() {
        assertEquals("slow_query",
                PromptBuilder.extractRouteTask("{\"task\":\"slow_query\"}").orElse(""));
    }

    @Test
    void parsesFencedJson() {
        assertEquals("slow_query",
                PromptBuilder.extractRouteTask("```json\n{\"task\": \"slow_query\"}\n```").orElse(""));
    }

    @Test
    void toleratesLeadingAndTrailingWhitespace() {
        assertEquals("database_metrics",
                PromptBuilder.extractRouteTask("   {\"task\":\"database_metrics\"}   ").orElse(""));
    }

    @Test
    void rejectsPlainTextWithoutJson() {
        assertTrue(PromptBuilder.extractRouteTask("我需要查询天气").isEmpty());
    }

    @Test
    void rejectsNonJsonSurroundingText() {
        assertTrue(PromptBuilder.extractRouteTask("结果如下：{\"task\":\"slow_query\"}").isEmpty());
    }

    @Test
    void rejectsMissingTaskField() {
        assertTrue(PromptBuilder.extractRouteTask("{\"other\":1}").isEmpty());
    }

    @Test
    void rejectsEmptyTaskValue() {
        assertTrue(PromptBuilder.extractRouteTask("{\"task\":\"\"}").isEmpty());
        assertTrue(PromptBuilder.extractRouteTask("{\"task\":null}").isEmpty());
    }

    @Test
    void rejectsNullAndBlankInput() {
        assertTrue(PromptBuilder.extractRouteTask(null).isEmpty());
        assertTrue(PromptBuilder.extractRouteTask("   ").isEmpty());
    }
}
