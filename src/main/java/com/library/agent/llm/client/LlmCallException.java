package com.library.agent.llm.client;

/**
 * LLM Chat Completions 调用失败的类型化异常。
 * <p>
 * 用于把网络、超时、HTTP 状态、空响应、解析失败等统一为带
 * {@code retryable} 标志的异常，供上层重试/降级/熔断决策。
 */
public class LlmCallException extends RuntimeException {

    /**
     * 是否可重试：网络/超时/429/5xx 为 true；4xx（参数、鉴权）与
     * 空内容/解析失败等确定性错误为 false。
     */
    private final boolean retryable;

    /**
     * HTTP 状态码；非 HTTP 错误（网络/超时/解析）为 null。
     */
    private final Integer httpStatus;

    public LlmCallException(String message, boolean retryable, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }

    public LlmCallException(String message, boolean retryable, Integer httpStatus) {
        this(message, retryable, httpStatus, null);
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    /**
     * 根据 HTTP 状态码判定是否可重试：429（限流）与 5xx（服务端错误）可重试，
     * 其余 4xx（参数、鉴权）属确定性错误，不可重试。
     */
    public static boolean isRetryableHttpStatus(int code) {
        return code == 429 || code >= 500;
    }
}
