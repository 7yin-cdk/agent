package com.library.agent.mapper;

import com.library.agent.entity.LlmCallRecord;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * LLM 调用记录 Mapper。
 */
@Mapper
public interface LlmCallRecordMapper {

    void insert(LlmCallRecord record);

    List<LlmCallRecord> selectByTraceId(String traceId);
}
