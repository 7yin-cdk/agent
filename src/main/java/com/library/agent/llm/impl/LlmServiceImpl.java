package com.library.agent.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.agent.llm.LlmService;
import dev.langchain4j.model.chat.ChatModel;
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

@Service
@RequiredArgsConstructor
public class LlmServiceImpl implements LlmService {


    @Value("${bailian.embedding-url}")
    private String embeddingUrl;

    @Value("${bailian.embedding-model}")
    private String embeddingModel;

    private static final int EMBED_DIMENSIONS = 1536;

    private final ChatModel toolChatModel;

    @Value("${bailian.chat-url}")
    private String chatUrl;

    @Value("${bailian.chat-model}")
    private String chatModel;

    @Value("${bailian.api-key}")
    private String apiKey;

    public String chat(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new RuntimeException("Prompt不能为空");
        }
        try {
            ObjectMapper objectMapper = new ObjectMapper();

            URL url = new URL(chatUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
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

    public List<List<Float>> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        final int BATCH_SIZE = 10; // ⭐ 百炼硬限制

        List<List<Float>> allResult = new ArrayList<>();

        try {
            ObjectMapper objectMapper = new ObjectMapper();

            for (int start = 0; start < texts.size(); start += BATCH_SIZE) {

                int end = Math.min(start + BATCH_SIZE, texts.size());
                List<String> batch = texts.subList(start, end);

                URL url = new URL(embeddingUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);

                // =====================
                // 构造请求
                // =====================
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

                // =====================
                // 读取响应
                // =====================
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

                // =====================
                // 错误处理
                // =====================
                if (root.has("error")) {
                    throw new RuntimeException("Embedding失败: " + root.get("error").toString());
                }

                JsonNode dataArray = root.get("data");

                if (dataArray == null || !dataArray.isArray()) {
                    throw new RuntimeException("返回data为空: " + responseStr);
                }

                // =====================
                // 解析向量
                // =====================
                for (JsonNode item : dataArray) {
                    JsonNode embeddingNode = item.get("embedding");

                    List<Float> vector = new ArrayList<>();
                    for (JsonNode node : embeddingNode) {
                        vector.add(node.floatValue());
                    }

                    allResult.add(vector);
                }
            }

            // =====================
            // 一致性校验
            // =====================
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


//    public String toolChat(String prompt){
//        return toolChatModel.chat(prompt);
//    }

}
