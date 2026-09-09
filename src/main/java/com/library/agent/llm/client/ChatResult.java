package com.library.agent.llm.client;

/**
 * 单次 Chat Completions 调用的结果。
 * <p>
 * hasUsage 为 false 表示服务端未返回 usage（部分流式场景不吐 token 统计），
 * 上层只在 hasUsage 为 true 时写 TokenUsage，避免把空统计当成有效值。
 */
public record ChatResult(String content, boolean hasUsage, int inputTokens, int outputTokens) {
}
