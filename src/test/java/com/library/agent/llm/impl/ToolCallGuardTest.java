package com.library.agent.llm.impl;

import com.library.agent.tool.ToolAccess;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolCallGuard 纯逻辑离线测试。
 * <p>
 * 覆盖归一化/落字判定与“严格/非严格、读/写、来源、候选”决策表，不依赖 Spring 与真实 LLM。
 */
class ToolCallGuardTest {

    private static final ToolAccess.Type READ = ToolAccess.Type.READ;
    private static final ToolAccess.Type WRITE = ToolAccess.Type.WRITE;
    private static final String EXPLICIT = "EXPLICIT_CURRENT";
    private static final String REFERENCED = "REFERENCED_CURRENT";
    private static final String HISTORY = "HISTORY_ONLY";

    /* ======================= normalize ======================= */

    @Test
    void normalizeLowercasesAndStripsWhitespace() {
        assertEquals("mydb1", ToolCallGuard.normalize(" MyDb  1 "));
    }

    @Test
    void normalizeMapsFullWidthHostToGroundingLiteral() {
        String fullWidthHost = "１９２．１６８．１．１００";
        String halfWidthHost = "192.168.1.100";
        assertEquals(ToolCallGuard.normalize(halfWidthHost), ToolCallGuard.normalize(fullWidthHost));
        assertTrue(ToolCallGuard.isGrounded("查一下 １９２．１６８．１．１００ 的慢查询", halfWidthHost));
    }

    @Test
    void normalizeKeepsUnderscoreSoDistinctDbNamesAreNotConflated() {
        /* 保留下划线：查询仅含 orderdb 时，order_db 不得被误判落字 */
        assertTrue(ToolCallGuard.isGrounded("看看 order_db 库", "order_db"));
        assertFalse(ToolCallGuard.isGrounded("看看 orderdb 库", "order_db"));
    }

    @Test
    void punctuationOnlyValueIsNotGrounded() {
        assertFalse(ToolCallGuard.isGrounded("任意内容", "：：，。"));
        assertEquals("", ToolCallGuard.normalize("：：，。"));
    }

    @Test
    void hostPortLiteralGroundsWhenPresentElseNot() {
        String value = "192.168.1.100:5432";
        assertTrue(ToolCallGuard.isGrounded("查 192.168.1.100:5432 库的慢查询", value));
        assertFalse(ToolCallGuard.isGrounded("看下 orderdb 库的慢查询", value));
    }

    /* ======================= 严格模式：写工具 ======================= */

    @Test
    void writeToolUngroundedExplicitIsAskedEvenWithSingleCandidate() {
        String message = ToolCallGuard.evaluate(true, WRITE, EXPLICIT, "instance",
                "192.168.1.100:5432", "把那个库的慢查询统计重置掉",
                List.of("192.168.1.100:5432"));
        assertNotNull(message);
        assertTrue(message.contains("instance"));
    }

    @Test
    void writeToolGroundedLiteralIsAllowed() {
        assertNull(ToolCallGuard.evaluate(true, WRITE, EXPLICIT, "instance",
                "192.168.1.100:5432", "把 192.168.1.100:5432 库的慢查询统计重置掉", List.of()));
    }

    @Test
    void writeToolUngroundedReferencedIsAsked() {
        String message = ToolCallGuard.evaluate(true, WRITE, REFERENCED, "instance",
                "192.168.1.100:5432", "把它重置掉", List.of("192.168.1.100:5432"));
        assertNotNull(message);
    }

    /* ======================= 严格模式：只读工具 ======================= */

    @Test
    void readToolUngroundedExplicitIsAskedEvenWithOneCandidate() {
        String message = ToolCallGuard.evaluate(true, READ, EXPLICIT, "instance",
                "192.168.1.100:5432", "帮我查慢查询", List.of("192.168.1.100:5432"));
        assertNotNull(message);
    }

    @Test
    void readToolUniqueMatchingReferencedCandidateIsAllowed() {
        assertNull(ToolCallGuard.evaluate(true, READ, REFERENCED, "instance",
                "192.168.1.100:5432", "它Top10有哪些", List.of("192.168.1.100:5432")));
    }

    @Test
    void readToolUniqueCandidateNormalizedFormStillMatches() {
        /* 全角冒号与半角冒号归一后等价，视为同一唯一指代 */
        assertNull(ToolCallGuard.evaluate(true, READ, REFERENCED, "instance",
                "192.168.1.100：5432", "它Top10有哪些", List.of("192.168.1.100:5432")));
    }

    @Test
    void readToolEmptyCandidatesAsksWithoutCandidateList() {
        String message = ToolCallGuard.evaluate(true, READ, REFERENCED, "instance",
                "192.168.1.100:5432", "它Top10有哪些", List.of());
        assertNotNull(message);
        assertFalse(message.contains("可选对象为"));
    }

    @Test
    void readToolAmbiguousCandidatesAsksListingCandidates() {
        List<String> candidates = List.of("192.168.1.100:5432", "10.0.0.5:5432");
        String message = ToolCallGuard.evaluate(true, READ, REFERENCED, "instance",
                "192.168.1.100:5432", "它Top10有哪些", candidates);
        assertNotNull(message);
        assertTrue(message.contains("192.168.1.100:5432"));
        assertTrue(message.contains("10.0.0.5:5432"));
    }

    @Test
    void readToolChosenValueNotAmongCandidatesAsks() {
        String message = ToolCallGuard.evaluate(true, READ, REFERENCED, "instance",
                "10.0.0.9:5432", "它Top10有哪些", List.of("192.168.1.100:5432"));
        assertNotNull(message);
    }

    @Test
    void readToolReferencedButGroundedIsAllowedRegardlessOfAmbiguity() {
        assertNull(ToolCallGuard.evaluate(true, READ, REFERENCED, "database",
                "order_db", "查 order_db 的慢查询", List.of("order_db", "test_db")));
    }

    /* ======================= 历史来源 ======================= */

    @Test
    void historyOnlyIsAskedInStrictMode() {
        String message = ToolCallGuard.evaluate(true, READ, HISTORY, "instance",
                "192.168.1.100:5432", "当前无该值", List.of("192.168.1.100:5432"));
        assertNotNull(message);
    }

    /* ======================= 非严格链路（自动巡检）保持旧语义 ======================= */

    @Test
    void nonStrictModeAllowsUngroundedExplicitAndReferenced() {
        assertNull(ToolCallGuard.evaluate(false, WRITE, EXPLICIT, "content",
                "生成的正文", "巡检Prompt不含正文", List.of()));
        assertNull(ToolCallGuard.evaluate(false, READ, REFERENCED, "instance",
                "192.168.1.100:5432", "任意", List.of("a", "b")));
    }

    @Test
    void nonStrictModeStillRejectsHistoryOnly() {
        String message = ToolCallGuard.evaluate(false, READ, HISTORY, "database",
                "order_db", "任意", List.of());
        assertNotNull(message);
        assertTrue(message.contains("database"));
    }
}
