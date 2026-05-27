package com.library.agent.controller;

import com.library.agent.dto.ChatRequest;
import com.library.agent.dto.ChatResponse;
import com.library.agent.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 聊天接口控制器。
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    /**
     * 发起一次 Agent 聊天。
     * <p>
     * 推荐使用 JSON 请求体传入 query 和 conversationId；保留 query 参数是为了兼容旧调用方式。
     *
     * @param request JSON 聊天请求
     * @param query 兼容旧接口的用户问题参数
     * @param conversationId 兼容旧接口的会话 ID 参数
     * @return 带会话 ID 的聊天响应
     */
    @PostMapping("/chat")
    public ChatResponse chat(
            @RequestBody(required = false) ChatRequest request,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "conversationId", required = false) String conversationId
    ) {
        ChatRequest chatRequest = request == null ? new ChatRequest() : request;
        if (chatRequest.getQuery() == null) {
            chatRequest.setQuery(query);
        }
        if (chatRequest.getConversationId() == null) {
            chatRequest.setConversationId(conversationId);
        }
        return agentService.chat(chatRequest);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestBody(required = false) ChatRequest request,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "conversationId", required = false) String conversationId
    ) {
        ChatRequest chatRequest = request == null ? new ChatRequest() : request;
        if (chatRequest.getQuery() == null) {
            chatRequest.setQuery(query);
        }
        if (chatRequest.getConversationId() == null) {
            chatRequest.setConversationId(conversationId);
        }
        return agentService.chatStream(chatRequest);
    }

}
