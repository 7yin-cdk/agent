package com.library.agent.llm;

import com.library.agent.context.AgentChatContext;
import com.library.agent.entity.AgentLongTermMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模块四 PromptBuilder 长期记忆注入单测（纯静态，离线）。
 * <p>
 * 覆盖：简单聊天/RAG 有记忆注入、无记忆整段省略；复杂任务 {{long_term_memory}}
 * 占位替换且空记忆不留占位符。
 */
class PromptBuilderLongTermMemoryTest {

    private AgentLongTermMemory memory(String category, String content) {
        AgentLongTermMemory m = new AgentLongTermMemory();
        m.setId(1L);
        m.setCategory(category);
        m.setContent(content);
        return m;
    }

    private List<AgentLongTermMemory> oneProfile() {
        return List.of(memory("USER_PROFILE", "运维小王负责 orders 库与 prod_cluster"));
    }

    @Test
    void simplePromptInjectsMemoryWhenPresent() {
        String prompt = PromptBuilder.buildSimplePrompt("我的核心库有哪些？", null, List.of(), oneProfile());
        int historyIdx = prompt.indexOf("### 当前会话历史");
        int memoryIdx = prompt.indexOf("### 长期记忆");
        assertTrue(memoryIdx > historyIdx, "长期记忆段应在历史段之后");
        assertTrue(prompt.contains("【记忆1】[用户画像] 运维小王负责 orders 库与 prod_cluster"),
                "应含中文类别标签的记忆行");
    }

    @Test
    void simplePromptOmitsMemorySectionWhenEmpty() {
        String prompt = PromptBuilder.buildSimplePrompt("你好", null, List.of(), List.of());
        assertFalse(prompt.contains("### 长期记忆"), "无记忆时不应注入记忆段");
        assertFalse(prompt.contains("{{long_term_memory}}"), "不应泄漏占位符");
    }

    @Test
    void ragPromptInjectsMemoryAfterReferences() {
        String prompt = PromptBuilder.buildRagPrompt(
                "orders 库最近有锁等待吗？", "orders 库最近有锁等待吗？", null,
                List.of(), List.of("【资料】orders 锁等待案例"), oneProfile());
        int refIdx = prompt.indexOf("### 参考资料");
        int memoryIdx = prompt.indexOf("### 长期记忆");
        assertTrue(refIdx >= 0 && memoryIdx > refIdx, "长期记忆段应追加在参考资料段之后");
        assertTrue(prompt.contains("【记忆1】[用户画像]"), "应注入画像记忆");
    }

    @Test
    void taskPromptReplacesPlaceholderWhenPresent() throws Exception {
        AgentChatContext ctx = new AgentChatContext();
        ctx.setQuery("orders 库慢查询");
        ctx.setConversationSummary("");
        ctx.setHistoryMessages(List.of());
        ctx.setLongTermMemories(oneProfile());
        String prompt = PromptBuilder.buildTaskPrompt(ctx, "{\"task\":\"slow_query\"}");
        assertFalse(prompt.contains("{{long_term_memory}}"), "占位符应被替换");
        assertTrue(prompt.contains("### 长期记忆"), "应注入记忆块");
        assertTrue(prompt.contains("【记忆1】[用户画像] 运维小王负责 orders 库与 prod_cluster"), "应含记忆行");
    }

    @Test
    void taskPromptNoPlaceholderLeakWhenEmpty() throws Exception {
        AgentChatContext ctx = new AgentChatContext();
        ctx.setQuery("orders 库慢查询");
        ctx.setConversationSummary("");
        ctx.setHistoryMessages(List.of());
        ctx.setLongTermMemories(List.of());
        String prompt = PromptBuilder.buildTaskPrompt(ctx, "{\"task\":\"slow_query\"}");
        assertFalse(prompt.contains("{{long_term_memory}}"), "无记忆也不应泄漏占位符");
        assertFalse(prompt.contains("### 长期记忆"), "无记忆不应有记忆段");
    }

    @Test
    void categoryLabels() {
        String prompt = PromptBuilder.buildSimplePrompt("q", null, List.of(), List.of(
                memory("USER_PROFILE", "a"), memory("PREFERENCE", "b"), memory("CONSTRAINT", "c"),
                memory("ENTITY", "d"), memory("EXPERIENCE", "e")));
        assertTrue(prompt.contains("【记忆1】[用户画像] a"));
        assertTrue(prompt.contains("【记忆2】[偏好] b"));
        assertTrue(prompt.contains("【记忆3】[约束] c"));
        assertTrue(prompt.contains("【记忆4】[实体] d"));
        assertTrue(prompt.contains("【记忆5】[经验] e"));
        assertEquals(1, countOccurrences(prompt, "### 长期记忆"));
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }
}
