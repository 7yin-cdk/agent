package com.library.agent.memory.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.config.LongTermMemoryProperties;
import com.library.agent.entity.AgentShortTermMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抽取 prompt 结构离线单测（不依赖 PG / LLM，确定性）。
 * <p>
 * 验证「增量主体 + 有界历史锚」：主体恒为当前轮 user/assistant；
 * 历史仅取最近 anchorWindow 条作上下文锚且与主体去重；主体为空返回 null。
 * 因同包访问包私有的 {@link LongTermMemoryServiceImpl#buildExtractPrompt}。
 */
class LongTermMemoryExtractPromptTest {

    private static final String EXTRACT_HEADER = "你是数据库运维 Agent 的长期记忆抽取器";

    private LongTermMemoryServiceImpl service;
    private LongTermMemoryProperties props;

    @BeforeEach
    void setUp() {
        props = new LongTermMemoryProperties();
        service = new LongTermMemoryServiceImpl(
                null, null, new ObjectMapper(), props, null);
    }

    /* ==================== 主体注入 ==================== */

    @Test
    void subjectLinesAlwaysInjectedUnderNewSection() {
        String query = "请记住，后续告警走企业微信推送(SELFTEST_ALERT)";
        String answer = "好的，已记住：告警走企业微信。";

        String prompt = service.buildExtractPrompt(query, answer, null);

        assertNotNull(prompt, "有主体时 prompt 不应为 null");
        assertTrue(prompt.contains(EXTRACT_HEADER), "应保留抽取器首行短语");
        assertTrue(prompt.contains("### 本轮新增"), "应含【本轮新增】小节");
        assertTrue(prompt.contains("user: " + query), "当前轮 user 应逐字注入主体");
        assertTrue(prompt.contains("assistant: " + answer), "当前轮 assistant 应逐字注入主体");
        assertFalse(prompt.contains("### 历史上下文（仅作理解，勿抽取）\n"),
                "无历史时不应出现历史上下文小节");
        assertTrue(prompt.trim().endsWith("### 输出\n[]"), "应以 JSON 输出占位收尾");
    }

    /* ==================== 锚有界 ==================== */

    @Test
    void historyAnchorBoundedByWindow() {
        props.getExtraction().setAnchorWindow(3);
        String query = "本轮问题 UNIQ_QUERY_0";
        String answer = "本轮回答 UNIQ_ANSWER_0";

        List<AgentShortTermMemory> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            history.add(msg("user", "历史消息 HIST_SEED_" + i + "_anchor"));
        }

        String prompt = service.buildExtractPrompt(query, answer, history);

        assertNotNull(prompt);
        assertTrue(prompt.contains("### 历史上下文（仅作理解，勿抽取）\n"), "有锚时应含历史上下文小节");
        for (int i = 5; i <= 7; i++) {
            assertTrue(prompt.contains("HIST_SEED_" + i + "_anchor"),
                    "最近的锚消息(索引 " + i + ")应保留");
        }
        for (int i = 0; i <= 4; i++) {
            assertFalse(prompt.contains("HIST_SEED_" + i + "_anchor"),
                    "超出 anchorWindow 的旧消息(索引 " + i + ")不应再注入");
        }
    }

    @Test
    void emptyHistoryProducesNoContextSection() {
        String prompt = service.buildExtractPrompt("问题 A", "回答 B", List.of());
        assertNotNull(prompt);
        assertFalse(prompt.contains("### 历史上下文"), "空历史不应出现历史上下文小节");

        String promptNull = service.buildExtractPrompt("问题 A", "回答 B", null);
        assertNotNull(promptNull);
        assertFalse(promptNull.contains("### 历史上下文"), "null 历史不应出现历史上下文小节");
    }

    /* ==================== 主体与锚去重 ==================== */

    @Test
    void currentTurnContentNotDuplicatedIntoAnchor() {
        props.getExtraction().setAnchorWindow(10);
        String query = "请记住，把备份改成每周日(BK_CUR)";
        String answer = "好的，已记住备份策略。";

        /* 生产 TC3 场景：turnMessages 含与当前轮相同的 user 内容 */
        List<AgentShortTermMemory> history = List.of(msg("user", query));

        String prompt = service.buildExtractPrompt(query, answer, history);

        assertNotNull(prompt);
        assertEquals(1, countOccurrences(prompt, "user: " + query),
                "当前轮 user 内容只应出现一次（作主体，不落入锚）");
    }

    /* ==================== 空主体 ==================== */

    @Test
    void emptySubjectReturnsNull() {
        assertNull(service.buildExtractPrompt(null, null, null), "无任何主体应返回 null");
        assertNull(service.buildExtractPrompt("   ", null, List.of()), "全空白主体应返回 null");
    }

    /* ==================== 辅助 ==================== */

    private static AgentShortTermMemory msg(String role, String content) {
        AgentShortTermMemory m = new AgentShortTermMemory();
        m.setRole(role);
        m.setContent(content);
        return m;
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
