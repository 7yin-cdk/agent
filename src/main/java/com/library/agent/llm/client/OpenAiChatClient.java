package com.library.agent.llm.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * OpenAI Chat Completions 兼容客户端（DeepSeek 与百炼 dashscope 兼容模式共用）。
 * <p>
 * 无状态、非 Spring Bean。负责一次 HTTP 请求的发起与 OpenAI 形状响应的解析，
 * 不做重试/降级/熔断（由 {@code ChatFailoverOrchestrator} 负责）。
 * 所有失败统一抛 {@link LlmCallException}，并按原因标记是否可重试。
 */
public class OpenAiChatClient {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int CHAT_READ_TIMEOUT_MS = 60_000;
    private static final int STREAM_READ_TIMEOUT_MS = 120_000;
    private static final double TEMPERATURE = 0.2;
    private static final int MAX_LOG_BODY_LENGTH = 500;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 非流式 Chat：返回完整回答文本与（可选的）usage。
     *
     * @throws LlmCallException 网络/HTTP/解析任一环节失败
     */
    public ChatResult chat(ChatProvider provider, String systemPrompt, String userPrompt) {
        HttpURLConnection connection = null;
        try {
            connection = open(provider, false);
            send(connection, buildBody(provider, systemPrompt, userPrompt, false));

            int code = connection.getResponseCode();
            String body = readBody(connection, code);
            if (code < 200 || code >= 300) {
                throw new LlmCallException("Chat 请求失败，HTTP=" + code + ", response=" + truncate(body),
                        LlmCallException.isRetryableHttpStatus(code), code);
            }

            JsonNode root = mapper.readTree(body);
            if (root.has("error")) {
                throw new LlmCallException("Chat 失败: " + root.get("error"), true, code);
            }
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new LlmCallException("Chat 返回 choices 为空", false, code);
            }
            JsonNode message = choices.get(0).get("message");
            if (message == null || message.get("content") == null) {
                throw new LlmCallException("Chat 返回 message.content 为空", false, code);
            }

            JsonNode usage = root.get("usage");
            boolean hasUsage = usage != null && usage.isObject();
            return new ChatResult(message.get("content").asText(),
                    hasUsage, inputTokens(usage), outputTokens(usage));
        } catch (LlmCallException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new LlmCallException("Chat 响应解析失败: " + e.getMessage(), false, null, e);
        } catch (IOException e) {
            throw new LlmCallException("Chat 网络请求失败: " + e.getMessage(), true, null, e);
        } finally {
            disconnect(connection);
        }
    }

    /**
     * 流式 Chat：逐个 token 回调 onDelta。
     * <p>
     * 每次向 onDelta 下发 token 前置位 {@code started}，供编排器区分
     * "首个 token 前失败（可重试/降级）"与"已下发后中断（不可回滚，向上抛）"。
     *
     * @throws LlmCallException 网络/HTTP/解析任一环节失败
     */
    public ChatResult streamChat(ChatProvider provider, String systemPrompt, String userPrompt,
                                 Consumer<String> onDelta, AtomicBoolean started) {
        HttpURLConnection connection = null;
        try {
            connection = open(provider, true);
            send(connection, buildBody(provider, systemPrompt, userPrompt, true));

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String body = readBody(connection, code);
                throw new LlmCallException("Chat 流式请求失败，HTTP=" + code + ", response=" + truncate(body),
                        LlmCallException.isRetryableHttpStatus(code), code);
            }

            StringBuilder content = new StringBuilder();
            boolean hasUsage = false;
            JsonNode usage = null;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                        continue;
                    }
                    String data = trimmed.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode root = mapper.readTree(data);
                    if (root.has("usage")) {
                        usage = root.get("usage");
                        hasUsage = true;
                    }
                    if (root.has("error")) {
                        throw new LlmCallException("Chat 流式失败: " + root.get("error"), true, code);
                    }
                    JsonNode choices = root.get("choices");
                    if (choices == null || !choices.isArray() || choices.isEmpty()) {
                        continue;
                    }
                    JsonNode delta = choices.get(0).path("delta").path("content");
                    if (!delta.isMissingNode() && !delta.isNull()) {
                        String token = delta.asText();
                        if (!token.isEmpty()) {
                            started.set(true);
                            onDelta.accept(token);
                            content.append(token);
                        }
                    }
                }
            }

            return new ChatResult(content.toString(), hasUsage, inputTokens(usage), outputTokens(usage));
        } catch (LlmCallException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new LlmCallException("Chat 流式响应解析失败: " + e.getMessage(), false, null, e);
        } catch (IOException e) {
            throw new LlmCallException("Chat 流式网络请求失败: " + e.getMessage(), true, null, e);
        } finally {
            disconnect(connection);
        }
    }

    /* 建立 HTTP 连接并设置通用头与超时 */
    private HttpURLConnection open(ChatProvider provider, boolean stream) throws IOException {
        URL url = new URL(provider.chatUrl());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + provider.apiKey());
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (stream) {
            connection.setRequestProperty("Accept", "text/event-stream");
        }
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(stream ? STREAM_READ_TIMEOUT_MS : CHAT_READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        return connection;
    }

    /* 写入请求体 */
    private void send(HttpURLConnection connection, String body) throws IOException {
        try (OutputStream os = connection.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    /* 组装 OpenAI 形状的请求体：system + user 两段消息 */
    private String buildBody(ChatProvider provider, String systemPrompt, String userPrompt,
                             boolean stream) throws JsonProcessingException {
        ObjectNode requestJson = mapper.createObjectNode();
        requestJson.put("model", provider.model());
        requestJson.put("temperature", TEMPERATURE);
        if (stream) {
            requestJson.put("stream", true);
        }

        ArrayNode messages = mapper.createArrayNode();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        requestJson.set("messages", messages);
        return mapper.writeValueAsString(requestJson);
    }

    private ObjectNode message(String role, String content) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", content == null ? "" : content);
        return node;
    }

    /* 按 HTTP 状态读响应体（2xx 读正常流，否则读错误流） */
    private String readBody(HttpURLConnection connection, int code) throws IOException {
        InputStream input = (code >= 200 && code < 300)
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (input == null) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }
        return body.toString();
    }

    private int inputTokens(JsonNode usage) {
        return usage == null ? 0 : usage.path("prompt_tokens").asInt(0);
    }

    private int outputTokens(JsonNode usage) {
        return usage == null ? 0 : usage.path("completion_tokens").asInt(0);
    }

    /* 截断过长响应体，避免日志被错误信息刷屏 */
    private String truncate(String text) {
        if (text == null || text.length() <= MAX_LOG_BODY_LENGTH) {
            return text == null ? "" : text;
        }
        return text.substring(0, MAX_LOG_BODY_LENGTH) + "...(truncated)";
    }

    private void disconnect(HttpURLConnection connection) {
        if (connection != null) {
            connection.disconnect();
        }
    }
}
