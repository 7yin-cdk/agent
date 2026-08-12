package com.library.agent.mapper;

import com.library.agent.entity.ConversationTrace;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 对话轮次追踪 Mapper。
 */
@Mapper
public interface ConversationTraceMapper {

    void insert(ConversationTrace trace);

    List<ConversationTrace> selectByUserId(Long userId);

    ConversationTrace selectByTraceId(String traceId);
}
