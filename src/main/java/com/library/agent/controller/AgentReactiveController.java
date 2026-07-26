package com.library.agent.controller;

import com.library.agent.auth.context.UserContextHolder;
import com.library.agent.conversation.dto.ConversationResponse;
import com.library.agent.conversation.dto.CreateConversationRequest;
import com.library.agent.conversation.service.ConversationService;
import com.library.agent.service.ReactiveStreamingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 响应式 SSE 流式对话端点（基于 SseEmitter）。
 * <p>
 * 将 LLM 的 token 流通过 SSE 协议实时推送到前端，
 * 前端以打字机效果展示。
 */
@Slf4j
@RestController
@RequestMapping("/agent/chat")
@RequiredArgsConstructor
public class AgentReactiveController {

    private final ReactiveStreamingService reactiveStreamingService;
    private final ConversationService conversationService;

    /**
     * 响应式流式对话。
     * <p>
     * 接受 JSON 请求体或 URL 参数，返回 SseEmitter。
     * SSE 事件序列：meta → status → delta* → done | error
     */
    @PostMapping(value = "/reactive/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatReactiveStream(
            @RequestBody(required = false) Map<String, String> body,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String conversationId
    ) {
        /* 1. 参数解析 */
        String resolvedQuery = query;
        if (resolvedQuery == null && body != null) {
            resolvedQuery = body.get("query");
        }
        if (resolvedQuery == null || resolvedQuery.isBlank()) {
            SseEmitter errEmitter = new SseEmitter();
            sendEvent(errEmitter, "error", Map.of("message", "query is required"));
            errEmitter.complete();
            return errEmitter;
        }

        String resolvedConvId = conversationId;
        if (resolvedConvId == null && body != null) {
            resolvedConvId = body.get("conversationId");
        }

        /* 2. 鉴权 */
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            SseEmitter errEmitter = new SseEmitter();
            sendEvent(errEmitter, "error", Map.of("message", "Unauthorized"));
            errEmitter.complete();
            return errEmitter;
        }

        /* 3. 解析或创建会话 */
        if (resolvedConvId == null || resolvedConvId.isBlank()) {
            CreateConversationRequest req = new CreateConversationRequest();
            String title = resolvedQuery.trim();
            req.setTitle(title.length() <= 20 ? title : title.substring(0, 20));
            ConversationResponse conv = conversationService.createConversation(req);
            resolvedConvId = conv.getConversationId();
        }

        final String finalConvId = resolvedConvId;
        final String finalQuery = resolvedQuery;

        /* 4. 核心流式管道 */
        return reactiveStreamingService.chatReactive(userId, finalConvId, finalQuery);
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception ignored) {
        }
    }
}
