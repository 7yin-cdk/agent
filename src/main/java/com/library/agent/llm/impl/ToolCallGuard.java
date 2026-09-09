package com.library.agent.llm.impl;

import com.library.agent.tool.ToolAccess;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 工具参数“落字校验 + 指代候选”纯逻辑助手。
 * <p>
 * 作用：在 ReAct 校验阶段判断 LLM 输出的工具参数值是否真实出现在用户本轮原话中
 * （防幻觉），并为“本轮指代（REFERENCED_CURRENT）”参数根据候选对象数量决定放行或追问。
 * 该方法为无副作用静态工具，便于离线单测，不含 Spring/LLM 依赖。
 */
public final class ToolCallGuard {

    /* 追问消息中最多展示的候选对象数量，避免模型给出过长候选列表撑爆回复 */
    private static final int CANDIDATE_DISPLAY_LIMIT = 5;

    private ToolCallGuard() {
    }

    /**
     * 文本归一化，对参数值与用户原话采用同一规则以保证“子串包含”语义一致：
     * 1) 全角转半角（含全角空格）；2) 统一小写；3) 仅保留字母、数字、下划线与连字符，
     * 丢弃空白与标点（冒号、点、斜杠、括号、引号等）。
     * <p>
     * 保留 {@code _}/{@code -} 是为了避免库名如 order_db 与 orderdb 被错误合并。
     *
     * @param text 原始文本，可为 null
     * @return 归一化文本；null 或纯标点输入返回空串
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEachOrdered(cp -> {
            if (cp == 0x3000) {
                /* 全角空格 U+3000 归一为普通空格，随后被丢弃 */
                cp = ' ';
            } else if (cp >= 0xFF01 && cp <= 0xFF5E) {
                /* 全角标点/字母（FF01~FF5E）平移映射为半角（21~7E） */
                cp = cp - 0xFEE0;
            }
            sb.appendCodePoint(cp);
        });
        String lowered = sb.toString().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lowered.length());
        lowered.codePoints().forEachOrdered(cp -> {
            if (Character.isLetterOrDigit(cp) || cp == '_' || cp == '-') {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    /**
     * 判断参数值是否“落字”于用户原话：即参数值归一化后作为连续文本片段出现在
     * 归一化的用户本轮原话中。归一值非空是前提，防空串对任意文本恒为真的误命中。
     *
     * @param userQuery 用户本轮原话（可为 null）
     * @param valueText 工具参数值（可为 null）
     * @return 落字命中返回 true
     */
    public static boolean isGrounded(String userQuery, String valueText) {
        String normalizedValue = normalize(valueText);
        if (normalizedValue.isEmpty()) {
            return false;
        }
        return normalize(userQuery).contains(normalizedValue);
    }

    /**
     * 依据规则判定单个必填工具参数是否放行。
     *
     * @param strict       是否启用严格落字校验（用户对话链路 true；自动巡检链路 false）
     * @param access       工具访问类型（读/写）
     * @param source       参数来源（EXPLICIT_CURRENT / REFERENCED_CURRENT / HISTORY_ONLY）
     * @param argumentName 参数名（用于生成追问文案）
     * @param valueText    参数值文本
     * @param userQuery    用户本轮原话
     * @param candidates   本轮指代候选对象（source=REFERENCED_CURRENT 时由 LLM 输出）
     * @return null 表示放行；否则返回面向用户的追问消息
     */
    public static String evaluate(boolean strict, ToolAccess.Type access,
                                  String source, String argumentName, String valueText,
                                  String userQuery, List<String> candidates) {
        /* 历史来源一律不放行，要求用户本轮明确提供（含非严格链路，保持现状） */
        if ("HISTORY_ONLY".equals(source)) {
            return askToProvide(argumentName);
        }
        /* 非严格链路（如自动巡检）：EXPLICIT / REFERENCED 维持原放行语义 */
        if (!strict) {
            return null;
        }
        /* 参数值确在本轮原话中出现 → 认定为用户确实提供，与工具类型无关 */
        if (isGrounded(userQuery, valueText)) {
            return null;
        }
        /* 写操作不允许按指代或推测放行 */
        if (access == ToolAccess.Type.WRITE) {
            return askLiteralValue(argumentName);
        }
        if ("REFERENCED_CURRENT".equals(source)) {
            List<String> distinct = distinctCandidates(candidates);
            if (distinct.size() == 1 && normalize(distinct.get(0)).equals(normalize(valueText))) {
                /* 唯一候选且与 LLM 选中值一致 → 指代无歧义，放行只读执行 */
                return null;
            }
            return askWhichObject(argumentName, distinct);
        }
        /* EXPLICIT_CURRENT 却未落字 → 疑似幻觉，读操作也需用户澄清 */
        return askLiteralValue(argumentName);
    }

    /**
     * 候选对象按归一化形式去重并保留首次出现顺序；null/空列表返回空列表。
     */
    private static List<String> distinctCandidates(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> distinct = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String key = normalize(candidate);
            if (key.isEmpty() || !seen.add(key)) {
                continue;
            }
            distinct.add(candidate.trim());
        }
        return distinct;
    }

    private static String askToProvide(String argumentName) {
        return "缺少工具参数「" + argumentName
                + "」：该值仅存在于历史上下文中。请在本轮提问中直接提供该参数的准确值。";
    }

    private static String askLiteralValue(String argumentName) {
        return "工具参数「" + argumentName
                + "」的值未在本轮提问中给出。为避免误操作，请在本轮明确说明该参数的准确取值。";
    }

    private static String askWhichObject(String argumentName, List<String> candidates) {
        StringBuilder message = new StringBuilder("工具参数「" + argumentName + "」指代不明确");
        if (!candidates.isEmpty()) {
            message.append("，可选对象为：");
            int showCount = Math.min(candidates.size(), CANDIDATE_DISPLAY_LIMIT);
            for (int i = 0; i < showCount; i++) {
                if (i > 0) {
                    message.append("、");
                }
                message.append(candidates.get(i));
            }
            if (candidates.size() > CANDIDATE_DISPLAY_LIMIT) {
                message.append(" 等 ").append(candidates.size()).append(" 个");
            }
        }
        message.append("。请明确您指的是哪一个。");
        return message.toString();
    }
}
