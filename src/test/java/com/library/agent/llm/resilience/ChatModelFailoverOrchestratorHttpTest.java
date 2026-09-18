package com.library.agent.llm.resilience;

import com.library.agent.llm.LlmExhaustedException;
import com.library.agent.llm.resilience.ChatModelFailoverOrchestrator.NamedChatModel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路二容错编排器的真实 HTTP 集成测试（本地 stub 网关，不连外网、不需重启应用）。
 * <p>
 * 与 {@code ChatModelFailoverOrchestratorTest} 的 mock 单测互补：单测假定
 * "langchain4j 会把 HTTP 状态码映射成可重试/确定性异常"，本类用真实的
 * {@link OpenAiChatModel} + {@code JdkHttpClient} 打本地 stub 服务，端到端验证该前提成立 ——
 * 即 builder(maxRetries=0) → HttpException → DefaultExceptionMapper → 编排器分类这条链路的真实行为。
 */
class ChatModelFailoverOrchestratorHttpTest {

    private static final String RESPONSE_JSON = """
            {"id":"1","object":"chat.completion","created":1,"model":"stub",
             "choices":[{"index":0,"message":{"role":"assistant","content":"answer-from-degrade"},
             "finish_reason":"stop"}]}
            """;

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    /**
     * 主模型网关持续 503（可重试）→ 同模型重试 1 次后降级到备用模型。
     * <p>
     * 断言主模型被请求 2 次（首次 + 1 次重试），备用模型被请求 1 次并最终作答，
     * 证明 5xx 在真实客户端上是"可重试 → 降级"而非直接失败。
     */
    @Test
    void realGateway503RetriesThenDegrades() {
        AtomicInteger primaryHits = new AtomicInteger();
        AtomicInteger degradeHits = new AtomicInteger();

        HttpServer primary = stub(503, "{\"error\":{\"message\":\"gateway unavailable\"}}", primaryHits);
        HttpServer degrade = stub(200, RESPONSE_JSON, degradeHits);

        ChatResponse response = orchestrator(
                List.of(model("bailian/qwen-plus", primary), model("deepseek/chat", degrade)), 1)
                .chat(request());

        assertEquals("answer-from-degrade", response.aiMessage().text());
        assertEquals(2, primaryHits.get(), "5xx 应先在同模型内重试一次");
        assertEquals(1, degradeHits.get());
    }

    /**
     * 主模型网关返回 400（确定性）→ 不重试，也不降级到备用模型，直接以友好文案结束。
     * <p>
     * 断言主模型仅被请求 1 次且备用模型 0 次，证明 4xx 在真实客户端上既不会被误判为可重试，
     * 也不会被误判为"换 provider 就能修好"。
     */
    @Test
    void realGateway400DoesNotRetryOrDegrade() {
        AtomicInteger primaryHits = new AtomicInteger();
        AtomicInteger degradeHits = new AtomicInteger();

        HttpServer primary = stub(400, "{\"error\":{\"message\":\"invalid model\"}}", primaryHits);
        HttpServer degrade = stub(200, RESPONSE_JSON, degradeHits);

        ChatModelFailoverOrchestrator orchestrator = orchestrator(
                List.of(model("bailian/qwen-plus", primary), model("deepseek/chat", degrade)), 2);

        LlmExhaustedException ex = assertThrows(LlmExhaustedException.class,
                () -> orchestrator.chat(request()));
        assertEquals(LlmExhaustedException.USER_FACING_MESSAGE, ex.getMessage());
        assertEquals(1, primaryHits.get(), "4xx 属确定性错误，不应重试");
        assertEquals(0, degradeHits.get(), "4xx 换 provider 仍会复现，不应降级");
    }

    /**
     * 主备网关同时 503 → 耗尽后抛统一友好文案，且技术细节保留在 cause 中。
     */
    @Test
    void realGatewayBothDownThrowsFriendlyMessage() {
        HttpServer primary = stub(503, "{\"error\":{\"message\":\"primary down\"}}", new AtomicInteger());
        HttpServer degrade = stub(503, "{\"error\":{\"message\":\"degrade down\"}}", new AtomicInteger());

        ChatModelFailoverOrchestrator orchestrator = orchestrator(
                List.of(model("bailian/qwen-plus", primary), model("deepseek/chat", degrade)), 0);

        LlmExhaustedException ex = assertThrows(LlmExhaustedException.class,
                () -> orchestrator.chat(request()));
        assertEquals(LlmExhaustedException.USER_FACING_MESSAGE, ex.getMessage());
        assertTrue(ex.getCause() instanceof dev.langchain4j.exception.InternalServerException,
                "503 应被映射为可重试异常，实际: " + ex.getCause());
    }

    private NamedChatModel model(String label, HttpServer server) {
        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .apiKey("test-key")
                .modelName("stub-model")
                .timeout(Duration.ofSeconds(10))
                /* 与生产配置一致：关闭库内重试，重试由编排器接管 */
                .maxRetries(0)
                .build();
        return new NamedChatModel(label, chatModel);
    }

    private static ChatModelFailoverOrchestrator orchestrator(List<NamedChatModel> models, int retryAttempts) {
        return new ChatModelFailoverOrchestrator(models, "react", retryAttempts, 0, Integer.MAX_VALUE, 10_000);
    }

    private static ChatRequest request() {
        return ChatRequest.builder().messages(UserMessage.from("ping")).build();
    }

    /* 启动一个固定状态码的 stub 网关，并统计收到的请求数 */
    private HttpServer stub(int status, String body, AtomicInteger hits) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> respond(exchange, status, body, hits));
            server.start();
            servers.add(server);
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("无法启动 stub HTTP 服务", e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body, AtomicInteger hits) {
        hits.incrementAndGet();
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            /* 错误响应也要带上 body，模拟真实网关返回的错误体 */
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        } catch (IOException ignored) {
            /* stub 服务在连接被提前关闭时可能失败，不影响断言 */
        } finally {
            exchange.close();
        }
    }
}
