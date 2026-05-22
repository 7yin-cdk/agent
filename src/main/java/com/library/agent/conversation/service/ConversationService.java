package com.library.agent.conversation.service;

import com.library.agent.conversation.dto.ConversationResponse;
import com.library.agent.conversation.dto.CreateConversationRequest;
import com.library.agent.conversation.dto.UpdateConversationTitleRequest;

import java.util.List;

/**
 * Agent 会话服务。
 * <p>
 * 负责会话创建、列表查询、归属校验、标题修改和会话状态维护。
 */
public interface ConversationService {

    /**
     * 为当前登录用户创建新会话。
     *
     * @param request 创建会话请求
     * @return 新会话信息
     */
    ConversationResponse createConversation(CreateConversationRequest request);

    /**
     * 查询当前登录用户的有效会话列表。
     *
     * @return 会话列表
     */
    List<ConversationResponse> listConversations();

    /**
     * 查询当前登录用户的指定会话。
     *
     * @param conversationId 会话 ID
     * @return 会话信息
     */
    ConversationResponse getConversation(String conversationId);

    /**
     * 修改当前登录用户的指定会话标题。
     *
     * @param conversationId 会话 ID
     * @param request 修改标题请求
     * @return 修改后的会话信息
     */
    ConversationResponse updateTitle(String conversationId, UpdateConversationTitleRequest request);

    /**
     * 逻辑删除当前登录用户的指定会话。
     *
     * @param conversationId 会话 ID
     */
    void deleteConversation(String conversationId);

    /**
     * 校验指定会话是否属于当前用户且处于有效状态。
     *
     * @param userId 当前登录用户 ID
     * @param conversationId 会话 ID
     */
    void validateConversationOwner(Long userId, String conversationId);

    /**
     * 更新会话活跃时间和消息数量。
     *
     * @param userId 当前登录用户 ID
     * @param conversationId 会话 ID
     * @param messageIncrement 本次新增的消息数量
     */
    void touchConversation(Long userId, String conversationId, Integer messageIncrement);
}
