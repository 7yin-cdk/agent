package com.library.agent.mapper;

import com.library.agent.entity.AgentConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Agent 会话表 Mapper。
 */
@Mapper
public interface AgentConversationMapper {

    /**
     * 插入新会话。
     */
    int insert(AgentConversation conversation);

    /**
     * 根据用户 ID 和会话 ID 查询会话。
     */
    AgentConversation selectByUserIdAndConversationId(
            @Param("userId") Long userId,
            @Param("conversationId") String conversationId
    );

    /**
     * 查询指定用户的有效会话列表。
     */
    List<AgentConversation> selectActiveByUserId(@Param("userId") Long userId);

    /**
     * 更新指定会话标题。
     */
    int updateTitle(
            @Param("userId") Long userId,
            @Param("conversationId") String conversationId,
            @Param("title") String title
    );

    /**
     * 将指定会话逻辑删除。
     */
    int logicalDelete(
            @Param("userId") Long userId,
            @Param("conversationId") String conversationId
    );

    /**
     * 更新指定会话的消息数量和最近消息时间。
     */
    int touch(
            @Param("userId") Long userId,
            @Param("conversationId") String conversationId,
            @Param("messageIncrement") Integer messageIncrement
    );
}
