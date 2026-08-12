package com.library.agent.observability;

import com.library.agent.entity.ConversationTrace;
import com.library.agent.entity.LlmCallRecord;
import com.library.agent.entity.ToolCallRecord;
import com.library.agent.mapper.ConversationTraceMapper;
import com.library.agent.mapper.LlmCallRecordMapper;
import com.library.agent.mapper.ToolCallRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 对话轮次追踪持久化服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationTraceService {

    private final ConversationTraceMapper traceMapper;
    private final LlmCallRecordMapper llmCallMapper;
    private final ToolCallRecordMapper toolCallMapper;

    /**
     * 持久化一次对话轮次的完整追踪数据。
     */
    @Transactional
    public void save(ConversationTraceCollector collector, String status, String errorMessage) {
        ConversationTraceSnapshot snapshot = collector.getSnapshot(status, errorMessage);
        ConversationTrace trace = toEntity(snapshot);
        traceMapper.insert(trace);

        for (LlmCallRecord record : collector.getLlmCalls()) {
            llmCallMapper.insert(record);
        }
        for (ToolCallRecord record : collector.getToolCalls()) {
            toolCallMapper.insert(record);
        }
        log.info("Saved conversation trace: traceId={}, llmCalls={}, toolCalls={}, totalTokens={}",
                snapshot.getTraceId(), snapshot.getLlmCallCount(),
                snapshot.getToolCallCount(), snapshot.getTotalTokens());
    }

    /**
     * 查询当前用户的 Trace 列表。
     */
    public List<ConversationTrace> listTraces(Long userId) {
        return traceMapper.selectByUserId(userId);
    }

    /**
     * 查询指定 Trace 的详情。
     */
    public ConversationTrace getTrace(String traceId) {
        return traceMapper.selectByTraceId(traceId);
    }

    /**
     * 查询指定 Trace 的 LLM 调用记录列表。
     */
    public List<LlmCallRecord> getLlmCalls(String traceId) {
        return llmCallMapper.selectByTraceId(traceId);
    }

    /**
     * 查询指定 Trace 的工具调用记录列表。
     */
    public List<ToolCallRecord> getToolCalls(String traceId) {
        return toolCallMapper.selectByTraceId(traceId);
    }

    private ConversationTrace toEntity(ConversationTraceSnapshot s) {
        ConversationTrace t = new ConversationTrace();
        t.setTraceId(s.getTraceId());
        t.setUserId(s.getUserId());
        t.setConversationId(s.getConversationId());
        t.setUserQuery(s.getUserQuery());
        t.setIntentType(s.getIntentType());
        t.setStartTime(s.getStartTime());
        t.setEndTime(s.getEndTime());
        t.setTotalDurationMs(s.getTotalDurationMs());
        t.setTotalInputTokens(s.getTotalInputTokens());
        t.setTotalOutputTokens(s.getTotalOutputTokens());
        t.setTotalTokens(s.getTotalTokens());
        t.setLlmCallCount(s.getLlmCallCount());
        t.setToolCallCount(s.getToolCallCount());
        t.setStatus(s.getStatus());
        t.setErrorMessage(s.getErrorMessage());
        t.setCreatedAt(s.getCreatedAt());
        return t;
    }
}
