package com.library.agent.conversation.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话消息响应 DTO。
 */
@Data
public class MessageResponse {

    /** 消息角色：user / assistant */
    private String role;

    /** 消息正文 */
    private String content;

    /** 消息顺序号 */
    private Long messageOrder;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
