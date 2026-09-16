package com.library.agent.enums;

/**
 * 意图识别常量
 */
public enum IntentType {
    KNOWLEDGE_BASE,
    COMPLEX_TASK;

    /**
     * 用户显式要求走知识库检索时的关键词。
     */
    private static final String[] KNOWLEDGE_BASE_KEYWORDS = {
            "查询知识库", "查询文档"
    };

    /**
     * 用户显式要求走工具调用时的关键词。
     */
    private static final String[] COMPLEX_TASK_KEYWORDS = {
            "调用工具", "使用工具"
    };

    /**
     * 按用户显式指令关键词快速匹配意图。
     * <p>
     * 只认用户主动说出的「查询知识库 / 查询文档」「调用工具 / 使用工具」，
     * 不做主题词猜测：同一个主题（如 VACUUM）问机制还是操作实例仍交由大模型判断。
     * 两条聊天链路共用本方法，保证口径一致。
     *
     * @param query 用户原始问题
     * @return 命中的意图；未命中返回 {@code null}，由调用方继续交给大模型判断
     */
    public static IntentType matchByKeyword(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        if (containsAny(query, KNOWLEDGE_BASE_KEYWORDS)) {
            return KNOWLEDGE_BASE;
        }
        if (containsAny(query, COMPLEX_TASK_KEYWORDS)) {
            return COMPLEX_TASK;
        }
        return null;
    }

    private static boolean containsAny(String query, String[] keywords) {
        for (String keyword : keywords) {
            if (query.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
