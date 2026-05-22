package com.library.agent.dto;

import lombok.Data;

/**
 * Agent 聊天响应
 */
@Data
public class ChatResponse {

    /**
     * 本次聊天所属会话 ID
     */
    private String conversationId;

    /**
     * Agent 回复内容
     */
    private String answer;
}
