package com.library.agent.conversation.dto;

import lombok.Data;

/**
 * 修改会话标题请求
 */
@Data
public class UpdateConversationTitleRequest {

    /**
     * 新会话标题
     */
    private String title;
}
