package com.library.agent.llm;

import com.library.agent.entity.AgentShortTermMemory;

import java.util.List;

/**
 * Prompt 构建工具类。
 * <p>
 * 该类只负责把已经准备好的会话历史、RAG 资料和用户问题拼装成 Prompt，
 * 不负责查询数据库、识别意图或调用大模型。
 */
public final class PromptBuilder {

    /**
     * 私有构造方法，避免工具类被实例化。
     */
    private PromptBuilder() {
    }

    /**
     * 构建普通聊天 Prompt。
     *
     * @param userQuestion 用户本轮问题
     * @param historyMessages 当前会话历史消息
     * @return 组装后的完整 Prompt
     */
    public static String buildSimplePrompt(String userQuestion, List<AgentShortTermMemory> historyMessages) {
        StringBuilder prompt = new StringBuilder();

        appendBaseRole(prompt);
        appendConversationHistory(prompt, historyMessages);

        prompt.append("### 任务定义\n");
        prompt.append("请根据用户的问题和当前会话历史，提供准确、清晰、有条理的回答。\n\n");

        prompt.append("### 约束\n");
        prompt.append("1. 回答必须基于事实、用户问题或当前会话历史。\n");
        prompt.append("2. 禁止编造不存在的事实。\n");
        prompt.append("3. 如果会话历史不足以判断指代关系，请明确说明需要用户补充信息。\n");
        prompt.append("4. 优先给出直接答案，再补充解释。\n\n");

        appendOutputFormat(prompt);
        appendUserQuestion(prompt, userQuestion);
        return prompt.toString();
    }

    /**
     * 构建带 RAG 资料的 Prompt。
     *
     * @param userQuestion 用户本轮问题
     * @param historyMessages 当前会话历史消息
     * @param ragTexts RAG 检索召回的资料片段
     * @return 组装后的完整 Prompt
     */
    public static String buildRagPrompt(
            String userQuestion,
            List<AgentShortTermMemory> historyMessages,
            List<String> ragTexts
    ) {
        StringBuilder prompt = new StringBuilder();

        appendBaseRole(prompt);
        appendConversationHistory(prompt, historyMessages);
        appendRagTexts(prompt, ragTexts);

        prompt.append("### 任务定义\n");
        prompt.append("请根据用户的问题、当前会话历史和参考资料，提供准确、清晰、有条理的回答。\n\n");

        prompt.append("### 约束\n");
        prompt.append("1. 回答必须优先基于参考资料。\n");
        prompt.append("2. 禁止编造参考资料中不存在的内容。\n");
        prompt.append("3. 如果参考资料与问题无关，请说明无法从资料中得到答案。\n");
        prompt.append("4. 如果用户问题依赖前文指代，请结合当前会话历史理解问题。\n");
        prompt.append("5. 优先给出直接答案，再补充解释。\n\n");

        appendOutputFormat(prompt);
        appendUserQuestion(prompt, userQuestion);
        return prompt.toString();
    }

    /**
     * 构建工具调用路径使用的 Prompt。
     *
     * @param userQuestion 用户本轮问题
     * @param historyMessages 当前会话历史消息
     * @return 组装后的完整 Prompt
     */
    public static String buildToolPrompt(String userQuestion, List<AgentShortTermMemory> historyMessages) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("### 角色定义与行为边界\n");
        prompt.append("你是一个具备工具调用能力的 AI 助手，需要结合当前会话历史理解用户要执行的任务。\n");
        prompt.append("如果需要调用工具，请根据用户真实意图选择合适工具；如果信息不足，请先说明缺少哪些参数。\n\n");

        appendConversationHistory(prompt, historyMessages);

        prompt.append("### 任务定义\n");
        prompt.append("请根据用户问题判断是否需要工具调用，并在工具能力范围内完成任务。\n\n");

        prompt.append("### 约束\n");
        prompt.append("1. 不要假设用户没有明确提供的关键参数。\n");
        prompt.append("2. 如果当前问题依赖前文，请优先结合会话历史理解指代关系。\n");
        prompt.append("3. 工具结果不足时，应说明限制，而不是编造结果。\n\n");

        appendUserQuestion(prompt, userQuestion);
        return prompt.toString();
    }

    /**
     * 兼容旧调用：构建普通聊天 Prompt。
     *
     * @param userQuestion 用户本轮问题
     * @return 组装后的完整 Prompt
     */
    public static String buildPrompt(String userQuestion) {
        return buildSimplePrompt(userQuestion, List.of());
    }

    /**
     * 兼容旧调用：conversationId 不在 PromptBuilder 中查询使用。
     *
     * @param userQuestion 用户本轮问题
     * @param conversationId 会话 ID，保留该参数仅用于兼容旧代码
     * @return 组装后的完整 Prompt
     */
    public static String buildPrompt(String userQuestion, String conversationId) {
        return buildSimplePrompt(userQuestion, List.of());
    }

    /**
     * 兼容旧调用：构建只包含 RAG 资料、不包含会话历史的 Prompt。
     *
     * @param userQuestion 用户本轮问题
     * @param ragTexts RAG 检索召回的资料片段
     * @return 组装后的完整 Prompt
     */
    public static String buildPrompt(String userQuestion, List<String> ragTexts) {
        return buildRagPrompt(userQuestion, List.of(), ragTexts);
    }

    /**
     * 追加基础角色设定。
     */
    private static void appendBaseRole(StringBuilder prompt) {
        prompt.append("### 角色定义与行为边界\n");
        prompt.append("你是一个专业的 AI 助手，具备严谨的逻辑推理能力和准确的信息处理能力。\n");
        prompt.append("你必须基于提供的信息进行回答，不得编造事实。\n");
        prompt.append("如果信息不足，请明确说明，而不是猜测。\n\n");
    }

    /**
     * 追加当前会话历史。
     * <p>
     * 历史消息由调用方提前查询并裁剪，本方法只负责稳定地格式化历史内容。
     */
    private static void appendConversationHistory(
            StringBuilder prompt,
            List<AgentShortTermMemory> historyMessages
    ) {
        prompt.append("### 当前会话历史\n");
        if (historyMessages == null || historyMessages.isEmpty()) {
            prompt.append("暂无历史会话。\n\n");
            return;
        }

        for (AgentShortTermMemory message : historyMessages) {
            if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            prompt.append(normalizeRole(message.getRole()))
                    .append(": ")
                    .append(message.getContent().trim())
                    .append("\n");
        }
        prompt.append("\n");
    }

    /**
     * 追加 RAG 召回资料。
     */
    private static void appendRagTexts(StringBuilder prompt, List<String> ragTexts) {
        prompt.append("### 参考资料\n");
        if (ragTexts == null || ragTexts.isEmpty()) {
            prompt.append("未检索到可用参考资料。\n\n");
            return;
        }

        for (int i = 0; i < ragTexts.size(); i++) {
            String text = ragTexts.get(i);
            if (text == null || text.isBlank()) {
                continue;
            }
            prompt.append("【资料").append(i + 1).append("】\n");
            prompt.append(text.trim()).append("\n\n");
        }
    }

    /**
     * 追加统一输出格式要求。
     */
    private static void appendOutputFormat(StringBuilder prompt) {
        prompt.append("### 输出格式\n");
        prompt.append("请按照如下格式输出：\n");
        prompt.append("【答案】\n");
        prompt.append("...\n\n");
        prompt.append("【解释】\n");
        prompt.append("...\n\n");
    }

    /**
     * 追加用户本轮问题。
     */
    private static void appendUserQuestion(StringBuilder prompt, String userQuestion) {
        prompt.append("### 用户问题\n");
        prompt.append(userQuestion == null ? "" : userQuestion.trim()).append("\n");
    }

    /**
     * 将数据库中的消息角色转换为 Prompt 中更容易理解的角色名称。
     */
    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "unknown";
        }
        return switch (role.trim().toLowerCase()) {
            case "user" -> "user";
            case "assistant" -> "assistant";
            case "tool" -> "tool";
            case "system" -> "system";
            default -> role.trim();
        };
    }
}
