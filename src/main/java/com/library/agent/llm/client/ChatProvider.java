package com.library.agent.llm.client;

/**
 * 一个 OpenAI Chat Completions 兼容的对话 provider 配置。
 * <p>
 * chatUrl 统一归一化为完整的 …/chat/completions 端点地址：
 * DeepSeek 配置已含完整路径，百炼 dashscope 兼容模式配置是 base URL（…/v1），
 * 二者形态不同，故在构造时补齐，避免备用模型请求打到错误路径返回 404。
 */
public record ChatProvider(String name, String chatUrl, String apiKey, String model) {

    public static ChatProvider of(String name, String baseUrl, String apiKey, String model) {
        return new ChatProvider(name, normalizeChatCompletionsUrl(baseUrl), apiKey, model);
    }

    /* 可观测展示名：provider/模型，如 deepseek/deepseek-v4-flash、bailian/qwen-plus */
    public String displayName() {
        return name + "/" + model;
    }

    /* 归一化：去除末尾斜杠后补全 /chat/completions */
    private static String normalizeChatCompletionsUrl(String base) {
        String value = base == null ? "" : base.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Chat provider url must not be empty");
        }
        if (value.endsWith("/chat/completions")) {
            return value;
        }
        return value.replaceAll("/+$", "") + "/chat/completions";
    }
}
