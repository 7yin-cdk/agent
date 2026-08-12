package com.library.agent.observability;

import com.library.agent.entity.LlmCallRecord;
import com.library.agent.entity.ToolCallRecord;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 对话轮次可观测数据采集器。
 * <p>
 * 在一轮对话中创建，收集所有 LLM 调用和工具调用的数据，
 * 对话结束后调用 {@link #finish} 并通过 {@link ConversationTraceService} 持久化。
 * <p>
 * 线程安全：本采集器在单次对话轮次内使用，不跨线程共享。
 */
public class ConversationTraceCollector {

    private final String traceId;
    private final Long userId;
    private final String conversationId;
    private final String userQuery;
    private final LocalDateTime startTime;
    private String intentType;
    private final List<LlmCallRecord> llmCalls = new ArrayList<>();
    private final List<ToolCallRecord> toolCalls = new ArrayList<>();

    public ConversationTraceCollector(Long userId, String conversationId, String userQuery) {
        this.traceId = "trace_" + UUID.randomUUID().toString().replace("-", "");
        this.userId = userId;
        this.conversationId = conversationId;
        this.userQuery = userQuery;
        this.startTime = LocalDateTime.now();
    }

    public String getTraceId() { return traceId; }

    public void setIntentType(String intentType) {
        this.intentType = intentType;
    }

    /**
     * 记录一次 LLM 调用。
     */
    public void recordLlmCall(String modelName, String callType,
                              String inputPrompt, String outputResponse,
                              int inputTokens, int outputTokens, long durationMs) {
        LlmCallRecord record = new LlmCallRecord();
        record.setTraceId(traceId);
        record.setCallSequence(llmCalls.size() + 1);
        record.setModelName(modelName);
        record.setCallType(callType);
        record.setInputPrompt(inputPrompt);
        record.setOutputResponse(outputResponse);
        record.setInputTokens(inputTokens);
        record.setOutputTokens(outputTokens);
        record.setDurationMs((int) durationMs);
        record.setCreatedAt(LocalDateTime.now());
        llmCalls.add(record);
    }

    /**
     * 记录一次工具调用。
     */
    public void recordToolCall(String toolName, String toolInput,
                               String toolOutput, boolean success, long durationMs) {
        ToolCallRecord record = new ToolCallRecord();
        record.setTraceId(traceId);
        record.setCallSequence(toolCalls.size() + 1);
        record.setToolName(toolName);
        record.setToolInput(toolInput);
        record.setToolOutput(toolOutput);
        record.setSuccess(success);
        record.setDurationMs((int) durationMs);
        record.setCreatedAt(LocalDateTime.now());
        toolCalls.add(record);
    }

    /**
     * 获取 LLM 调用记录列表（只读）。
     */
    public List<LlmCallRecord> getLlmCalls() {
        return List.copyOf(llmCalls);
    }

    /**
     * 获取工具调用记录列表（只读）。
     */
    public List<ToolCallRecord> getToolCalls() {
        return List.copyOf(toolCalls);
    }

    /**
     * 计算汇总数据，返回可用于持久化的 ConversationTrace 快照。
     */
    public ConversationTraceSnapshot getSnapshot(String status, String errorMessage) {
        int totalInput = 0;
        int totalOutput = 0;
        for (LlmCallRecord c : llmCalls) {
            totalInput += c.getInputTokens() != null ? c.getInputTokens() : 0;
            totalOutput += c.getOutputTokens() != null ? c.getOutputTokens() : 0;
        }

        ConversationTraceSnapshot s = new ConversationTraceSnapshot();
        s.setTraceId(traceId);
        s.setUserId(userId);
        s.setConversationId(conversationId);
        s.setUserQuery(userQuery);
        s.setIntentType(intentType);
        s.setStartTime(startTime);
        s.setEndTime(LocalDateTime.now());
        long duration = java.time.Duration.between(startTime, s.getEndTime()).toMillis();
        s.setTotalDurationMs((int) duration);
        s.setTotalInputTokens(totalInput);
        s.setTotalOutputTokens(totalOutput);
        s.setTotalTokens(totalInput + totalOutput);
        s.setLlmCallCount(llmCalls.size());
        s.setToolCallCount(toolCalls.size());
        s.setStatus(status);
        s.setErrorMessage(errorMessage);
        s.setCreatedAt(LocalDateTime.now());
        return s;
    }
}
