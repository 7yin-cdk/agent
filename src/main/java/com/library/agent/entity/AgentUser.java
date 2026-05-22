package com.library.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 用户实体类
 * 对应 PostgreSQL 表 agent_user
 */
@Data
public class AgentUser {

    /**
     * 用户 ID
     */
    private Long id;

    /**
     * 登录用户名，唯一
     */
    private String username;

    /**
     * BCrypt 加密后的密码
     */
    private String passwordHash;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 用户状态：ACTIVE 正常，DISABLED 禁用
     */
    private String status;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
