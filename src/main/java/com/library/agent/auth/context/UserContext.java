package com.library.agent.auth.context;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 当前登录用户上下文，存储在 Redis 和 ThreadLocal 中
 */
@Data
public class UserContext {

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 登录用户名
     */
    private String username;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 登录时间
     */
    private LocalDateTime loginTime;
}
