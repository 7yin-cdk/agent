package com.library.agent.observability.controller;

import com.library.agent.auth.context.UserContextHolder;
import com.library.agent.entity.ConversationTrace;
import com.library.agent.entity.LlmCallRecord;
import com.library.agent.entity.ToolCallRecord;
import com.library.agent.observability.ConversationTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 可观测性数据查询接口。
 */
@RestController
@RequestMapping("/agent/observability")
@RequiredArgsConstructor
public class ObservabilityController {

    private final ConversationTraceService traceService;

    /**
     * 查询当前用户的 Trace 列表。
     */
    @GetMapping("/traces")
    public List<ConversationTrace> listTraces() {
        Long userId = UserContextHolder.getUserId();
        return traceService.listTraces(userId);
    }

    /**
     * 查询指定 Trace 的详情（含 LLM 调用和工具调用记录）。
     */
    @GetMapping("/traces/{traceId}")
    public Map<String, Object> getTraceDetail(@PathVariable String traceId) {
        ConversationTrace trace = traceService.getTrace(traceId);
        List<LlmCallRecord> llmCalls = traceService.getLlmCalls(traceId);
        List<ToolCallRecord> toolCalls = traceService.getToolCalls(traceId);
        return Map.of(
                "trace", trace,
                "llmCalls", llmCalls,
                "toolCalls", toolCalls
        );
    }

    /**
     * 汇总统计。
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        Long userId = UserContextHolder.getUserId();
        List<ConversationTrace> traces = traceService.listTraces(userId);

        int totalTraces = traces.size();
        long totalTokens = traces.stream().mapToLong(t -> t.getTotalTokens() != null ? t.getTotalTokens() : 0).sum();
        long successCount = traces.stream().filter(t -> "SUCCESS".equals(t.getStatus())).count();
        long avgDuration = traces.isEmpty() ? 0
                : (long) traces.stream().mapToInt(t -> t.getTotalDurationMs() != null ? t.getTotalDurationMs() : 0).average().orElse(0);

        return Map.of(
                "totalTraces", totalTraces,
                "totalTokens", totalTokens,
                "successCount", successCount,
                "errorCount", totalTraces - successCount,
                "avgDurationMs", avgDuration
        );
    }
}
