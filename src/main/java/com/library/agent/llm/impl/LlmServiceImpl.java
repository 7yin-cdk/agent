package com.library.agent.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.agent.llm.LlmService;
import com.library.agent.tracing.TracingConstant;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
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

    private final Tracer tracer;

    public String chat(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new RuntimeException("Prompt不能为空");
        }

        Span span = tracer.nextSpan()
                .name(TracingConstant.LLM_DEEPSEEK_CHAT)
                .start();

        long startMs = System.currentTimeMillis();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag(TracingConstant.TAG_MODEL, chatModel);
            span.tag(TracingConstant.TAG_PROMPT_LENGTH, String.valueOf(prompt.length()));

            String result = doChat(prompt);

            span.tag(TracingConstant.TAG_RESPONSE_LENGTH, String.valueOf(result.length()));
            return result;

        } catch (Exception e) {
            span.error(e);
            throw new RuntimeException("百炼Chat失败", e);
        } finally {
            span.tag(TracingConstant.TAG_DURATION_MS,
                    String.valueOf(System.currentTimeMillis() - startMs));
            span.end();
        }
    }

    @Override
    public void chatStream(String prompt, Consumer<String> onDelta) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new RuntimeException("Prompt cannot be empty");
        }
        if (onDelta == null) {
            throw new RuntimeException("onDelta cannot be null");
        }

        Span span = tracer.nextSpan()
                .name(TracingConstant.LLM_DEEPSEEK_CHAT_STREAM)
                .start();

        long startMs = System.currentTimeMillis();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag(TracingConstant.TAG_MODEL, chatModel);
            span.tag(TracingConstant.TAG_PROMPT_LENGTH, String.valueOf(prompt.length()));

            doChatStream(prompt, onDelta);

        } catch (Exception e) {
            span.error(e);
            throw new RuntimeException("Streaming chat failed", e);
        } finally {
            span.tag(TracingConstant.TAG_DURATION_MS,
                    String.valueOf(System.currentTimeMillis() - startMs));
            span.end();
        }
    }

    public List<List<Float>> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        Span span = tracer.nextSpan()
                .name(TracingConstant.LLM_BAILIAN_EMBED)
                .start();

        long startMs = System.currentTimeMillis();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag(TracingConstant.TAG_MODEL, embeddingModel);
            span.tag("gen_ai.input.count", String.valueOf(texts.size()));

            List<List<Float>> result = doEmbed(texts);

            span.tag("gen_ai.output.count", String.valueOf(result.size()));
            return result;

        } catch (Exception e) {
            span.error(e);
            throw new RuntimeException("百炼Embedding失败", e);
        } finally {
            span.tag(TracingConstant.TAG_DURATION_MS,
                    String.valueOf(System.currentTimeMillis() - startMs));
            span.end();
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

        Span span = tracer.nextSpan()
                .name(TracingConstant.LLM_BAILIAN_RERANK)
                .start();

        long startMs = System.currentTimeMillis();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag(TracingConstant.TAG_MODEL, rerankModel);
            span.tag("gen_ai.rerank.document_count", String.valueOf(documents.size()));
            span.tag("gen_ai.rerank.top_n", String.valueOf(topN));

            List<Integer> result = doRerank(query, documents, topN, minScore);

            span.tag("gen_ai.rerank.result_count", String.valueOf(result.size()));
            return result;

        } catch (Exception e) {
            span.error(e);
            throw new RuntimeException("Qwen3 rerank failed", e);
        } finally {
            span.tag(TracingConstant.TAG_DURATION_MS,
                    String.valueOf(System.currentTimeMillis() - startMs));
            span.end();
        }
    }

    /* ==================== 原始实现（从原方法提取的内部方法） ==================== */

    private String doChat(String prompt) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();

            URL url = new URL(chatUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + deepseekApiKey);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);

            ObjectNode requestJson = objectMapper.createObjectNode();
            requestJson.put("model", chatModel);
            requestJson.put("temperature", 0.2);

            ArrayNode messages = objectMapper.createArrayNode();

            ObjectNode systemMessage = objectMapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", "你是一个严谨的知识库问答助手。请只根据用户提供的上下文回答问题，不要编造。");
            messages.add(systemMessage);

            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);

            requestJson.set("messages", messages);

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
                throw new RuntimeException("Chat失败: " + root.get("error").toString());
            }

            if (code < 200 || code >= 300) {
                throw new RuntimeException("Chat请求失败，HTTP状态码=" + code + ", response=" + responseStr);
            }

            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() == 0) {
                throw new RuntimeException("Chat返回choices为空: " + responseStr);
            }

            JsonNode message = choices.get(0).get("message");
            if (message == null || message.get("content") == null) {
                throw new RuntimeException("Chat返回message.content为空: " + responseStr);
            }

            return message.get("content").asText();

        } catch (Exception e) {
            throw new RuntimeException("百炼Chat失败", e);
        }
    }

    private void doChatStream(String prompt, Consumer<String> onDelta) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();

            URL url = new URL(chatUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + deepseekApiKey);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);

            ObjectNode requestJson = objectMapper.createObjectNode();
            requestJson.put("model", chatModel);
            requestJson.put("temperature", 0.2);
            requestJson.put("stream", true);

            ArrayNode messages = objectMapper.createArrayNode();

            ObjectNode systemMessage = objectMapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", "你是一个企业内部 AI 助手。只有当用户提示词中明确包含“参考资料”或“Reference Materials”部分时，才将当前问题视为知识库问答，并严格依据参考资料回答；如果用户输入中没有参考资料这几个字，则按普通问答处理，可以基于你的通用预训练知识正常回答。不要编造事实、工具结果或文档内容。");
            messages.add(systemMessage);

            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);

            requestJson.set("messages", messages);

            String requestBody = objectMapper.writeValueAsString(requestJson);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream inputStream = (code >= 200 && code < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            if (code < 200 || code >= 300) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
                throw new RuntimeException("Chat request failed, HTTP status=" + code + ", response=" + response);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmedLine = line.trim();
                    if (trimmedLine.isEmpty() || !trimmedLine.startsWith("data:")) {
                        continue;
                    }

                    String data = trimmedLine.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }

                    JsonNode root = objectMapper.readTree(data);
                    if (root.has("error")) {
                        throw new RuntimeException("Chat failed: " + root.get("error").toString());
                    }

                    JsonNode choices = root.get("choices");
                    if (choices == null || !choices.isArray() || choices.size() == 0) {
                        continue;
                    }

                    JsonNode content = choices.get(0).path("delta").path("content");
                    if (!content.isMissingNode() && !content.isNull()) {
                        String token = content.asText();
                        if (!token.isEmpty()) {
                            onDelta.accept(token);
                        }
                    }
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Streaming chat failed", e);
        }
    }

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
}
