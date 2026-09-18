package com.library.agent.llm.resilience;

import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.io.IOException;
import java.util.List;

/**
 * 链路二（langchain4j {@link ChatModel}）的容错编排器。
 * <p>
 * 口径与链路一完全一致：429/5xx 先在同模型内退避重试，重试耗尽后降级到下一个模型，
 * 连续可重试失败累计触发熔断；4xx 等确定性错误则立即终止，不重试也不降级。
 * 终止与全部模型耗尽后抛 {@code LlmExhaustedException}（统一友好文案，技术细节只进日志）。
 * <p>
 * 分类依据是 langchain4j 已映射好的异常类型而非 HTTP 状态码：底层 {@code JdkHttpClient}
 * 抛出的 {@code HttpException} 会在 {@code OpenAiChatModel.chat()} 的重试循环内部被
 * {@code DefaultExceptionMapper} 转成类型化异常，因此这里无需也无法直接读状态码。
 * <p>
 * 模型自身的重试应通过 {@code maxRetries(0)} 关闭，避免"库内重试 × 编排器重试"的次数放大
 * 导致与链路一的每 provider 尝试次数不一致。
 */
public class ChatModelFailoverOrchestrator {

    /**
     * 可降级模型条目。
     * <p>
     * {@link ChatModel} 自身不携带名称，故显式附带观测用展示名（如 bailian/qwen-plus），
     * 与链路一的 {@code ChatProvider.displayName()} 语义对齐。
     */
    public record NamedChatModel(String label, ChatModel model) {
    }

    private final FailoverExecutor<NamedChatModel> executor;
    private final String scene;

    /**
     * @param models                  按优先级排列的模型列表，下标越小越优先（主模型在前）
     * @param scene                   场景标识，仅用于日志
     * @param retryAttempts           单模型内的额外重试次数（不含首次）
     * @param backoffMs               线性退避基准毫秒
     * @param circuitFailureThreshold 连续可重试失败达到该值即熔断该模型
     * @param circuitOpenMs           熔断持续时长，到期后进入半开单探针
     */
    public ChatModelFailoverOrchestrator(List<NamedChatModel> models, String scene,
                                        int retryAttempts, long backoffMs,
                                        int circuitFailureThreshold, long circuitOpenMs) {
        this.executor = new FailoverExecutor<>(models, NamedChatModel::label,
                retryAttempts, backoffMs, circuitFailureThreshold, circuitOpenMs);
        this.scene = scene;
    }

    /**
     * 依次尝试各模型直到成功。
     *
     * @param request langchain4j 对话请求
     * @return 实际命中模型的响应
     * @throws com.library.agent.llm.LlmExhaustedException 所有模型均失败
     */
    public ChatResponse chat(ChatRequest request) {
        FailoverExecutor.Attempt<NamedChatModel, ChatResponse> attempt =
                executor.execute(scene, entry -> entry.model().chat(request),
                        ChatModelFailoverOrchestrator::classify);
        return attempt.result();
    }

    /**
     * 失败处置判定，与链路一的 429/5xx 可重试、4xx 立即终止口径一致。
     * <p>
     * langchain4j 的映射关系：500–599/429/408 → {@code RetriableException}（重试并允许降级），
     * 400/401/403/404 等其余 4xx → {@code NonRetriableException}（立即终止，不重试也不降级）；
     * 连接失败与超时无 HTTP 状态码，被包装为 {@code LangChain4jException(cause=IOException)}，
     * 与链路一"网络/超时按可重试处理"保持一致。未识别的异常无从判断可恢复性，按立即终止处理。
     */
    private static FailoverExecutor.Disposition classify(Throwable error) {
        if (error instanceof RetriableException) {
            return FailoverExecutor.Disposition.RETRY;
        }
        if (error instanceof NonRetriableException) {
            return FailoverExecutor.Disposition.ABORT;
        }
        return hasIoCause(error)
                ? FailoverExecutor.Disposition.RETRY : FailoverExecutor.Disposition.ABORT;
    }

    /* 沿 cause 链查找 IO 故障：langchain4j 对非 HTTP 错误统一包装一层，原始 IOException 在 cause 上 */
    private static boolean hasIoCause(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
