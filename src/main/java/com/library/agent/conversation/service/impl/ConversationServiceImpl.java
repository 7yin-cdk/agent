package com.library.agent.conversation.service.impl;

import com.library.agent.auth.context.UserContextHolder;
import com.library.agent.conversation.dto.ConversationResponse;
import com.library.agent.conversation.dto.CreateConversationRequest;
import com.library.agent.conversation.dto.UpdateConversationTitleRequest;
import com.library.agent.conversation.service.ConversationService;
import com.library.agent.entity.AgentConversation;
import com.library.agent.mapper.AgentConversationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String DEFAULT_TITLE = "新会话";

    private final AgentConversationMapper agentConversationMapper;

    @Override
    @Transactional
    public ConversationResponse createConversation(CreateConversationRequest request) {
        Long userId = requireCurrentUserId();
        LocalDateTime now = LocalDateTime.now();

        AgentConversation conversation = new AgentConversation();
        conversation.setUserId(userId);
        conversation.setConversationId(generateConversationId());
        conversation.setTitle(normalizeTitle(request == null ? null : request.getTitle()));
        conversation.setStatus(STATUS_ACTIVE);
        conversation.setMessageCount(0);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);

        agentConversationMapper.insert(conversation);
        return toResponse(conversation);
    }

    @Override
    public List<ConversationResponse> listConversations() {
        Long userId = requireCurrentUserId();
        return agentConversationMapper.selectActiveByUserId(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public ConversationResponse getConversation(String conversationId) {
        Long userId = requireCurrentUserId();
        AgentConversation conversation = getActiveConversation(userId, conversationId);
        return toResponse(conversation);
    }

    @Override
    @Transactional
    public ConversationResponse updateTitle(String conversationId, UpdateConversationTitleRequest request) {
        Long userId = requireCurrentUserId();
        validateConversationOwner(userId, conversationId);

        String title = normalizeTitle(request == null ? null : request.getTitle());
        int rows = agentConversationMapper.updateTitle(userId, conversationId, title);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        return toResponse(getActiveConversation(userId, conversationId));
    }

    @Override
    @Transactional
    public void deleteConversation(String conversationId) {
        Long userId = requireCurrentUserId();
        int rows = agentConversationMapper.logicalDelete(userId, conversationId);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
    }

    @Override
    public void validateConversationOwner(Long userId, String conversationId) {
        getActiveConversation(userId, conversationId);
    }

    @Override
    @Transactional
    public void touchConversation(Long userId, String conversationId, Integer messageIncrement) {
        validateConversationOwner(userId, conversationId);
        agentConversationMapper.touch(userId, conversationId, messageIncrement);
    }

    private AgentConversation getActiveConversation(Long userId, String conversationId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话ID不能为空");
        }

        AgentConversation conversation = agentConversationMapper.selectByUserIdAndConversationId(
                userId,
                conversationId.trim()
        );
        if (conversation == null || !STATUS_ACTIVE.equals(conversation.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        return conversation;
    }

    private Long requireCurrentUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return userId;
    }

    private String generateConversationId() {
        return "conv_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String normalizeTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return DEFAULT_TITLE;
        }
        String trimmed = title.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200);
    }

    private ConversationResponse toResponse(AgentConversation conversation) {
        ConversationResponse response = new ConversationResponse();
        response.setConversationId(conversation.getConversationId());
        response.setTitle(conversation.getTitle());
        response.setStatus(conversation.getStatus());
        response.setMessageCount(conversation.getMessageCount());
        response.setLastMessageAt(conversation.getLastMessageAt());
        response.setCreatedAt(conversation.getCreatedAt());
        response.setUpdatedAt(conversation.getUpdatedAt());
        return response;
    }
}
