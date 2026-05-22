package com.library.agent.dto;

import lombok.Data;

/**
 * Agent 聊天请求
 */
@Data
public class ChatRequest {

    /**
     * 会话 ID，可为空；为空时后端自动创建新会话
     */
    private String conversationId;

    /**
     * 用户问题
     */
    private String query;
}
