package com.library.agent.mapper;

import com.library.agent.entity.AgentConversationSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentConversationSummaryMapper {

    AgentConversationSummary selectByUserIdAndConversationId(
            @Param("userId") String userId,
            @Param("conversationId") String conversationId
    );

    int upsert(AgentConversationSummary summary);
}
