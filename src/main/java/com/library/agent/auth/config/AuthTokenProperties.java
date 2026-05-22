package com.library.agent.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录令牌配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "auth.token")
public class AuthTokenProperties {

    /**
     * 令牌有效秒数，默认 7 天
     */
    private Long expireSeconds = 604800L;

    /**
     * Redis 登录上下文 key 前缀
     */
    private String redisKeyPrefix = "agent:login:token:";
}
