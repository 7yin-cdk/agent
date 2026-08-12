package com.library.agent.mapper;

import com.library.agent.entity.ToolCallRecord;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 工具调用记录 Mapper。
 */
@Mapper
public interface ToolCallRecordMapper {

    void insert(ToolCallRecord record);

    List<ToolCallRecord> selectByTraceId(String traceId);
}
