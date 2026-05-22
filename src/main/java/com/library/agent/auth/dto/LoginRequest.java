package com.library.agent.auth.dto;

import lombok.Data;

/**
 * 用户登录请求
 */
@Data
public class LoginRequest {

    /**
     * 登录用户名
     */
    private String username;

    /**
     * 登录密码
     */
    private String password;
}
