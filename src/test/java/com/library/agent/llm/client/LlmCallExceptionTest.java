package com.library.agent.llm.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可重试错误分类测试：网络/429/5xx 可重试，4xx 与确定性错误不可重试。
 */
class LlmCallExceptionTest {

    @Test
    void rateLimitAndServerErrorsAreRetryable() {
        assertTrue(LlmCallException.isRetryableHttpStatus(429), "429 限流应可重试");
        assertTrue(LlmCallException.isRetryableHttpStatus(500), "5xx 应可重试");
        assertTrue(LlmCallException.isRetryableHttpStatus(502), "5xx 应可重试");
    }

    @Test
    void clientErrorsAreNotRetryable() {
        assertFalse(LlmCallException.isRetryableHttpStatus(400), "参数错误不可重试");
        assertFalse(LlmCallException.isRetryableHttpStatus(401), "鉴权错误不可重试");
        assertFalse(LlmCallException.isRetryableHttpStatus(403), "权限错误不可重试");
    }
}
