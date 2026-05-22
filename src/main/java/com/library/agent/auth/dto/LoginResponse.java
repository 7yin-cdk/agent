package com.library.agent.auth.dto;

import lombok.Data;

/**
 * 用户登录响应
 */
@Data
public class LoginResponse {

    /**
     * 登录令牌，后续请求放入 Authorization: Bearer {token}
     */
    private String token;

    /**
     * 令牌有效秒数
     */
    private Long expireSeconds;

    /**
     * 当前登录用户基础信息
     */
    private UserInfoResponse user;
}
