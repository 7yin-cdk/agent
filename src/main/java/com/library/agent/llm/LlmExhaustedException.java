package com.library.agent.llm;

/**
 * 全部 LLM provider 重试/降级均失败后抛出的最终异常。
 * <p>
 * 对外 message 使用统一友好文案（SSE 主链路会把它透传给前端展示），
 * 具体技术细节保留在 cause 中并已由编排器写日志，避免泄露给最终用户。
 */
public class LlmExhaustedException extends RuntimeException {

    public LlmExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
