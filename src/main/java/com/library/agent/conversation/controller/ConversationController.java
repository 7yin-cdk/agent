package com.library.agent.conversation.controller;

import com.library.agent.auth.context.UserContextHolder;
import com.library.agent.conversation.dto.ConversationResponse;
import com.library.agent.conversation.dto.CreateConversationRequest;
import com.library.agent.conversation.dto.MessageResponse;
import com.library.agent.conversation.dto.UpdateConversationTitleRequest;
import com.library.agent.conversation.service.ConversationService;
import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.memory.ShortTermMemoryService;
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
    private final ShortTermMemoryService shortTermMemoryService;

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

    /**
     * 查询指定会话的全部历史消息。
     */
    @GetMapping("/{conversationId}/messages")
    public List<MessageResponse> listMessages(@PathVariable String conversationId) {
        Long userId = UserContextHolder.getUserId();
        conversationService.validateConversationOwner(userId, conversationId);
        List<AgentShortTermMemory> messages =
                shortTermMemoryService.listMessagesByConversation(userId, conversationId);
        return messages.stream()
                .map(m -> {
                    MessageResponse r = new MessageResponse();
                    r.setRole(m.getRole());
                    r.setContent(m.getContent());
                    r.setMessageOrder(m.getMessageOrder());
                    r.setCreatedAt(m.getCreatedAt());
                    return r;
                })
                .toList();
    }
}
