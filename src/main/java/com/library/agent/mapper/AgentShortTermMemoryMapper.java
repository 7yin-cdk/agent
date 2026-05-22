package com.library.agent.mapper;

import com.library.agent.entity.AgentShortTermMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Agent 短期记忆表 Mapper。
 */
@Mapper
public interface AgentShortTermMemoryMapper {

    /**
     * 插入一条短期记忆消息。
     */
    int insert(AgentShortTermMemory memory);

    /**
     * 根据主键查询短期记忆消息。
     */
    AgentShortTermMemory selectById(@Param("id") Long id);

    /**
     * 查询指定用户指定会话的最近消息。
     */
    List<AgentShortTermMemory> selectRecentMessages(
            @Param("userId") String userId,
            @Param("conversationId") String conversationId,
            @Param("limit") Integer limit
    );

    /**
     * 查询指定会话的下一条消息顺序号。
     */
    Long selectNextMessageOrder(
            @Param("userId") String userId,
            @Param("conversationId") String conversationId
    );

    /**
     * 逻辑删除指定会话下的所有短期记忆消息。
     */
    int logicalDeleteByConversation(
            @Param("userId") String userId,
            @Param("conversationId") String conversationId
    );

    /**
     * 物理删除已过期的短期记忆消息。
     */
    int deleteExpired();
}
