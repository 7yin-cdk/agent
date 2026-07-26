package com.library.agent.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.llm.LlmService;
import com.library.agent.llm.QueryRewriteResult;
import com.library.agent.llm.QueryRewriteService;
import com.library.agent.tracing.TracingConstant;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteServiceImpl implements QueryRewriteService {

    private static final int HISTORY_LIMIT = 8;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LlmService llmService;
    private final Tracer tracer;

    @Override
    public QueryRewriteResult rewrite(
            String query,
            String conversationSummary,
            List<AgentShortTermMemory> historyMessages
    ) {
        if (query == null || query.isBlank()) {
            return QueryRewriteResult.unchanged(query);
        }

        String originalQuery = query.trim();
        Span span = tracer.nextSpan()
                .name(TracingConstant.QUERY_REWRITE)
                .start();

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            String result = llmService.chat(buildRewritePrompt(originalQuery, conversationSummary, historyMessages));
            QueryRewriteResult rewriteResult = parseResult(originalQuery, result);
            log.info("Query rewrite result, rewritten={}, original={}, rewrittenQuery={}",
                    rewriteResult.isRewritten(),
                    rewriteResult.getOriginalQuery(),
                    rewriteResult.getRewrittenQuery()
            );
            return rewriteResult;
        } catch (Exception e) {
            span.error(e);
            log.warn("Query rewrite failed, fallback to original query", e);
            return QueryRewriteResult.unchanged(originalQuery);
        } finally {
            span.end();
        }
    }

    private String buildRewritePrompt(
            String query,
            String conversationSummary,
            List<AgentShortTermMemory> historyMessages
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是企业知识库问答系统中的检索查询改写器。\n");
        prompt.append("任务：结合会话摘要和最近历史，将当前用户问题改写成适合向量检索的完整查询。\n\n");

        prompt.append("### 改写规则\n");
        prompt.append("1. 只补全指代、省略、上下文实体和检索关键词，不要回答问题。\n");
        prompt.append("2. 必须保留用户原始意图，不要扩大问题范围，不要加入历史中没有的事实。\n");
        prompt.append("3. 如果当前问题已经完整清晰，rewrittenQuery 直接返回原问题。\n");
        prompt.append("4. rewrittenQuery 应是一句自然语言检索 query，适合 embedding 检索。\n");
        prompt.append("5. 只输出 JSON，不要输出 Markdown 或解释。\n\n");

        prompt.append("### 会话摘要\n");
        if (conversationSummary == null || conversationSummary.isBlank()) {
            prompt.append("暂无。\n\n");
        } else {
            prompt.append(conversationSummary.trim()).append("\n\n");
        }

        prompt.append("### 最近历史\n");
        List<AgentShortTermMemory> limitedHistory = limitHistory(historyMessages);
        if (limitedHistory.isEmpty()) {
            prompt.append("暂无。\n\n");
        } else {
            for (AgentShortTermMemory message : limitedHistory) {
                if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                    continue;
                }
                prompt.append(normalizeRole(message.getRole()))
                        .append(": ")
                        .append(message.getContent().trim())
                        .append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("### 当前用户问题\n");
        prompt.append(query).append("\n\n");

        prompt.append("### 输出 JSON 格式\n");
        prompt.append("{\"rewrittenQuery\":\"改写后的检索 query\",\"rewritten\":true}\n");
        return prompt.toString();
    }

    private QueryRewriteResult parseResult(String originalQuery, String modelResult) throws Exception {
        if (modelResult == null || modelResult.isBlank()) {
            return QueryRewriteResult.unchanged(originalQuery);
        }

        String json = extractJsonObject(modelResult.trim());
        JsonNode root = OBJECT_MAPPER.readTree(json);
        String rewrittenQuery = root.path("rewrittenQuery").asText(originalQuery).trim();
        if (rewrittenQuery.isBlank()) {
            rewrittenQuery = originalQuery;
        }

        boolean contentChanged = !rewrittenQuery.equals(originalQuery);
        boolean modelMarkedRewritten = root.path("rewritten").asBoolean(contentChanged);

        QueryRewriteResult result = new QueryRewriteResult();
        result.setOriginalQuery(originalQuery);
        result.setRewrittenQuery(rewrittenQuery);
        result.setRewritten(modelMarkedRewritten && contentChanged);
        return result;
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private List<AgentShortTermMemory> limitHistory(List<AgentShortTermMemory> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return List.of();
        }
        int fromIndex = Math.max(0, historyMessages.size() - HISTORY_LIMIT);
        return historyMessages.subList(fromIndex, historyMessages.size());
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "unknown";
        }
        return switch (role.trim().toLowerCase()) {
            case "user" -> "user";
            case "assistant" -> "assistant";
            case "tool" -> "tool";
            case "system" -> "system";
            default -> role.trim();
        };
    }
}
