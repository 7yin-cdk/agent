package com.library.agent.conversation.dto;

import lombok.Data;

/**
 * 创建会话请求
 */
@Data
public class CreateConversationRequest {

    /**
     * 会话标题，可为空
     */
    private String title;
}
