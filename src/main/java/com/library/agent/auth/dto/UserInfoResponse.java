package com.library.agent.auth.dto;

import lombok.Data;

/**
 * 登录用户基础信息
 */
@Data
public class UserInfoResponse {

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
}
