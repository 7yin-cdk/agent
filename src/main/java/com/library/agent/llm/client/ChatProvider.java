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

    /**
     * 归一化后的 base URL（剥掉 {@code /chat/completions} 后缀）。
     * <p>
     * langchain4j 的 {@code OpenAiChatModel.baseUrl} 需要 base 形态，由其自行拼接端点路径；
     * 而本 record 的 chatUrl 是完整端点形态。此处复用同一份归一化结果反推，
     * 避免在配置里再维护一个可能与 chat-url 漂移的 base-url 属性。
     *
     * @return base URL，如 https://dashscope.aliyuncs.com/compatible-mode/v1
     */
    public String baseUrl() {
        String suffix = "/chat/completions";
        return chatUrl.endsWith(suffix) ? chatUrl.substring(0, chatUrl.length() - suffix.length()) : chatUrl;
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
