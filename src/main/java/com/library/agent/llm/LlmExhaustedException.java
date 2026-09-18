package com.library.agent.llm;

/**
 * 全部 LLM provider 重试/降级均失败后抛出的最终异常。
 * <p>
 * 对外 message 使用统一友好文案（SSE 主链路会把它透传给前端展示），
 * 具体技术细节保留在 cause 中并已由编排器写日志，避免泄露给最终用户。
 */
public class LlmExhaustedException extends RuntimeException {

    /**
     * 全链路 LLM 不可用时的统一对外文案。
     * <p>
     * 两条调用链路（自研编排器与 langchain4j ChatModel）共用，保证用户看到的提示一致，
     * 且与后台日志中的技术细节完全解耦。
     */
    public static final String USER_FACING_MESSAGE = "AI 服务暂时不可用，请稍后再试。";

    public LlmExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
