package com.library.agent.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.agent.llm.LlmService;
import com.library.agent.llm.client.ChatProvider;
import com.library.agent.llm.client.ChatResult;
import com.library.agent.llm.client.OpenAiChatClient;
import com.library.agent.llm.resilience.ChatFailoverOrchestrator;
import com.library.agent.llm.resilience.ChatOutcome;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Service
public class LlmServiceImpl implements LlmService {

    @Value("${bailian.embedding-url}")
    private String embeddingUrl;

    @Value("${bailian.embedding-model}")
    private String embeddingModel;

    @Value("${deepseek.chat-url}")
    private String chatUrl;

    @Value("${deepseek.chat-model}")
    private String chatModel;

    @Value("${bailian.rerank-url}")
    private String rerankUrl;

    @Value("${bailian.rerank-model:qwen3-rerank}")
    private String rerankModel;

    private static final int EMBED_DIMENSIONS = 1536;
    private static final int MAX_PROMPT_TAG_LENGTH = 500;

    @Value("${bailian.api-key}")
    private String baiLianApiKey;

    @Value("${deepseek.api-key}")
    private String deepseekApiKey;

    @Value("${bailian.chat-url:}")
    private String bailianChatUrl;

    @Value("${bailian.chat-model:}")
    private String bailianChatModel;

    @Value("${agent.llm.retry-attempts:2}")
    private int llmRetryAttempts;

    @Value("${agent.llm.retry-backoff-ms:500}")
    private long llmRetryBackoffMs;

    @Value("${agent.llm.circuit-failure-threshold:5}")
    private int llmCircuitFailureThreshold;

    @Value("${agent.llm.circuit-open-ms:30000}")
    private long llmCircuitOpenMs;

    /* 普通 chat 与流式 chat 的系统提示，主备 provider 共用，保证降级后行为等价 */
    private static final String CHAT_SYSTEM_PROMPT =
            "你是一个严谨的知识库问答助手。请只根据用户提供的上下文回答问题，不要编造。";
    private static final String STREAM_SYSTEM_PROMPT =
            "你是一个企业内部 AI 助手。只有当用户提示词中明确包含“参考资料”或“Reference Materials”部分时，"
                    + "才将当前问题视为知识库问答，并严格依据参考资料回答；如果用户输入中没有参考资料这几个字，"
                    + "则按普通问答处理，可以基于你的通用预训练知识正常回答。不要编造事实、工具结果或文档内容。";

    /**
     * 容错编排器：provider 顺序为 deepseek(主) → 百炼 qwen-plus(备)。
     */
    private ChatFailoverOrchestrator chatOrchestrator;

    private final ThreadLocal<TokenUsage> lastTokenUsage = new ThreadLocal<>();
    private final ThreadLocal<String> lastUsedModel = new ThreadLocal<>();

    /**
     * 依赖注入完成后按配置构建主备 provider，无外部依赖时备用 provider 自动缺省。
     */
    @PostConstruct
    public void initChatOrchestrator() {
        List<ChatProvider> providers = new ArrayList<>();
        providers.add(ChatProvider.of("deepseek", chatUrl, deepseekApiKey, chatModel));
        if (hasSecondaryConfig()) {
            providers.add(ChatProvider.of("bailian", bailianChatUrl, baiLianApiKey, bailianChatModel));
        }
        chatOrchestrator = new ChatFailoverOrchestrator(new OpenAiChatClient(), providers,
                llmRetryAttempts, llmRetryBackoffMs,
                llmCircuitFailureThreshold, llmCircuitOpenMs);
    }

    private boolean hasSecondaryConfig() {
        return isNotBlank(bailianChatUrl) && isNotBlank(bailianChatModel) && isNotBlank(baiLianApiKey);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @Override
    public String chat(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new RuntimeException("Prompt不能为空");
        }

        clearLastCallState();
        ChatOutcome outcome = chatOrchestrator.chat(CHAT_SYSTEM_PROMPT, prompt);
        applyOutcome(outcome);
        return outcome.result().content();
    }

    @Override
    public void chatStream(String prompt, Consumer<String> onDelta) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new RuntimeException("Prompt cannot be empty");
        }
        if (onDelta == null) {
            throw new RuntimeException("onDelta cannot be null");
        }

        clearLastCallState();
        ChatOutcome outcome = chatOrchestrator.chatStream(STREAM_SYSTEM_PROMPT, prompt, onDelta);
        applyOutcome(outcome);
    }

    /**
     * 记录本次成功调用的结果到 ThreadLocal：
     * 仅当服务端返回 usage 时才写 token 用量（空统计不覆盖），
     * 并始终记录实际命中 provider 的模型标识（provider/model）。
     */
    private void applyOutcome(ChatOutcome outcome) {
        ChatResult result = outcome.result();
        if (result.hasUsage()) {
            lastTokenUsage.set(new TokenUsage(
                    result.inputTokens(),
                    result.outputTokens(),
                    result.inputTokens() + result.outputTokens()));
        }
        lastUsedModel.set(outcome.provider().displayName());
    }

    /* 每次新调用开始时清空上次残留的用量与模型，避免读到过期值 */
    private void clearLastCallState() {
        lastTokenUsage.remove();
        lastUsedModel.remove();
    }

    @Override
    public List<List<Float>> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return doEmbed(texts);
        } catch (Exception e) {
            throw new RuntimeException("百炼Embedding失败", e);
        }
    }

    @Override
    public List<Float> embed(String text) {
        List<String> texts = new ArrayList<>();
        texts.add(text);

        List<List<Float>> embeddings = embed(texts);
        if (embeddings == null || embeddings.isEmpty()) {
            throw new RuntimeException("Embedding结果为空");
        }

        return embeddings.get(0);
    }

    @Override
    public List<Integer> rerank(String query, List<String> documents, int topN, double minScore) {
        if (query == null || query.trim().isEmpty()) {
            throw new RuntimeException("Rerank query cannot be empty");
        }
        if (documents == null || documents.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return doRerank(query, documents, topN, minScore);
        } catch (Exception e) {
            throw new RuntimeException("Qwen3 rerank failed", e);
        }
    }

    /* ==================== Embedding 实现 ==================== */

    private List<List<Float>> doEmbed(List<String> texts) {
        final int BATCH_SIZE = 10;

        List<List<Float>> allResult = new ArrayList<>();

        try {
            ObjectMapper objectMapper = new ObjectMapper();

            for (int start = 0; start < texts.size(); start += BATCH_SIZE) {

                int end = Math.min(start + BATCH_SIZE, texts.size());
                List<String> batch = texts.subList(start, end);

                URL url = new URL(embeddingUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + baiLianApiKey);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);

                ObjectNode requestJson = objectMapper.createObjectNode();
                requestJson.put("model", embeddingModel);
                requestJson.put("dimensions", EMBED_DIMENSIONS);
                requestJson.put("encoding_format", "float");

                ArrayNode inputArray = objectMapper.createArrayNode();
                for (String text : batch) {
                    inputArray.add(text == null ? "" : text);
                }

                requestJson.set("input", inputArray);

                String requestBody = objectMapper.writeValueAsString(requestJson);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();

                InputStream inputStream = (code >= 200 && code < 300)
                        ? conn.getInputStream()
                        : conn.getErrorStream();

                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }

                String responseStr = response.toString();

                JsonNode root = objectMapper.readTree(responseStr);

                if (root.has("error")) {
                    throw new RuntimeException("Embedding失败: " + root.get("error").toString());
                }

                JsonNode dataArray = root.get("data");

                if (dataArray == null || !dataArray.isArray()) {
                    throw new RuntimeException("返回data为空: " + responseStr);
                }

                for (JsonNode item : dataArray) {
                    JsonNode embeddingNode = item.get("embedding");

                    List<Float> vector = new ArrayList<>();
                    for (JsonNode node : embeddingNode) {
                        vector.add(node.floatValue());
                    }

                    allResult.add(vector);
                }
            }

            if (allResult.size() != texts.size()) {
                throw new RuntimeException(
                        "向量数量不一致 input="
                                + texts.size()
                                + ", output="
                                + allResult.size()
                );
            }

            return allResult;

        } catch (Exception e) {
            throw new RuntimeException("百炼Embedding失败", e);
        }
    }

    /* ==================== Rerank 实现 ==================== */

    private List<Integer> doRerank(String query, List<String> documents, int topN, double minScore) {
        List<String> validDocuments = new ArrayList<>();
        List<Integer> originalIndexes = new ArrayList<>();

        for (int i = 0; i < documents.size(); i++) {
            String document = documents.get(i);
            if (document == null || document.trim().isEmpty()) {
                continue;
            }
            validDocuments.add(document.trim());
            originalIndexes.add(i);
        }

        if (validDocuments.isEmpty()) {
            return new ArrayList<>();
        }

        int safeTopN = Math.min(Math.max(topN, 1), validDocuments.size());

        try {
            ObjectMapper objectMapper = new ObjectMapper();

            URL url = new URL(rerankUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + baiLianApiKey);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);

            ObjectNode requestJson = objectMapper.createObjectNode();
            requestJson.put("model", rerankModel);

            ObjectNode input = objectMapper.createObjectNode();
            input.put("query", query.trim());

            ArrayNode documentArray = objectMapper.createArrayNode();
            for (String document : validDocuments) {
                documentArray.add(document);
            }
            input.set("documents", documentArray);
            requestJson.set("input", input);

            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("top_n", safeTopN);
            parameters.put("return_documents", false);
            requestJson.set("parameters", parameters);

            String requestBody = objectMapper.writeValueAsString(requestJson);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();

            InputStream inputStream = (code >= 200 && code < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            String responseStr = response.toString();
            JsonNode root = objectMapper.readTree(responseStr);

            if (root.has("error")) {
                throw new RuntimeException("Rerank failed: " + root.get("error").toString());
            }

            if (code < 200 || code >= 300) {
                throw new RuntimeException("Rerank request failed, HTTP status=" + code + ", response=" + responseStr);
            }

            JsonNode results = root.path("output").path("results");
            if (!results.isArray()) {
                throw new RuntimeException("Rerank output.results is empty or invalid: " + responseStr);
            }

            List<Integer> rerankedIndexes = new ArrayList<>();

            for (JsonNode item : results) {
                JsonNode indexNode = item.get("index");
                JsonNode scoreNode = item.get("relevance_score");
                if (indexNode == null || !indexNode.canConvertToInt()) {
                    continue;
                }
                if (scoreNode == null || !scoreNode.isNumber()) {
                    continue;
                }
                int validDocumentIndex = indexNode.asInt();
                double relevanceScore = scoreNode.asDouble();
                if (relevanceScore < minScore) {
                    continue;
                }
                if (validDocumentIndex < 0 || validDocumentIndex >= originalIndexes.size()) {
                    continue;
                }
                rerankedIndexes.add(originalIndexes.get(validDocumentIndex));
            }

            return rerankedIndexes;

        } catch (Exception e) {
            throw new RuntimeException("Qwen3 rerank failed", e);
        }
    }

    @Override
    public TokenUsage getLastTokenUsage() {
        return lastTokenUsage.get();
    }

    @Override
    public String getLastUsedModel() {
        return lastUsedModel.get();
    }

    @Override
    public void clearLastTokenUsage() {
        clearLastCallState();
    }
}
