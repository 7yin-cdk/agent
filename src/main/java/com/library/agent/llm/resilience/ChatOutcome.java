package com.library.agent.llm.resilience;

import com.library.agent.llm.client.ChatProvider;
import com.library.agent.llm.client.ChatResult;

/**
 * 一次成功 LLM 调用的完整结果。
 * <p>
 * 在编排器内部决定实际由哪个 provider 提供服务后，把 provider 连同结果一并返回，
 * 使上层可观测层能记录"本次真正调用的是哪个 provider/模型"（而非配置的默认模型）。
 */
public record ChatOutcome(ChatProvider provider, ChatResult result) {
}
