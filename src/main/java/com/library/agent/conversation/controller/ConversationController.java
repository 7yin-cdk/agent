package com.library.agent.conversation.controller;

import com.library.agent.conversation.dto.ConversationResponse;
import com.library.agent.conversation.dto.CreateConversationRequest;
import com.library.agent.conversation.dto.UpdateConversationTitleRequest;
import com.library.agent.conversation.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 会话管理接口控制器。
 */
@RestController
@RequestMapping("/agent/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    /**
     * 创建当前登录用户的新会话。
     */
    @PostMapping
    public ConversationResponse createConversation(@RequestBody(required = false) CreateConversationRequest request) {
        return conversationService.createConversation(request);
    }

    /**
     * 查询当前登录用户的有效会话列表。
     */
    @GetMapping
    public List<ConversationResponse> listConversations() {
        return conversationService.listConversations();
    }

    /**
     * 查询当前登录用户的指定会话信息。
     */
    @GetMapping("/{conversationId}")
    public ConversationResponse getConversation(@PathVariable String conversationId) {
        return conversationService.getConversation(conversationId);
    }

    /**
     * 修改当前登录用户的指定会话标题。
     */
    @PatchMapping("/{conversationId}/title")
    public ConversationResponse updateTitle(
            @PathVariable String conversationId,
            @RequestBody UpdateConversationTitleRequest request
    ) {
        return conversationService.updateTitle(conversationId, request);
    }

    /**
     * 逻辑删除当前登录用户的指定会话。
     */
    @DeleteMapping("/{conversationId}")
    public String deleteConversation(@PathVariable String conversationId) {
        conversationService.deleteConversation(conversationId);
        return "delete success";
    }
}
