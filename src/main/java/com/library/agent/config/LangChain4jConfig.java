package com.library.agent.config;

import com.library.agent.llm.client.ChatProvider;
import com.library.agent.llm.resilience.ChatModelFailoverOrchestrator;
import com.library.agent.llm.resilience.ChatModelFailoverOrchestrator.NamedChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.List;

@Configuration
public class LangChain4jConfig {

    /* ReAct 工具编排链路二的场景标识，用于容错日志 */
    private static final String SCENE_REACT = "react";
    private static final long CHAT_TIMEOUT_SECONDS = 60;

    /**
     * 主对话模型（百炼 qwen-plus），供 ReAct 工具编排使用。
     * <p>
     * {@code maxRetries(0)}：关闭 langchain4j 内置重试，重试统一由
     * {@link ChatModelFailoverOrchestrator} 接管。否则"库内 2 次 × 编排器 2 次"
     * 会让单模型尝试次数达到 9 次，与链路一的每 provider 3 次不一致。
     * 异常映射仍由库在重试循环内完成，故类型化异常照常可用。
     * <p>
     * 标注 @Primary：引入评测用 judgeChatModel 与降级模型后容器内存在多个 ChatModel Bean，
     * 若未指定默认候选，按类型注入会抛 NoUniqueBeanDefinitionException。
     */
    @Bean("chatModel")
    @Primary
    public ChatModel chatModel(
            @Value("${bailian.chat-url}") String baseUrl,
            @Value("${bailian.api-key}") String apiKey,
            @Value("${bailian.chat-model}") String modelName
    ) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(CHAT_TIMEOUT_SECONDS))
                .maxRetries(0)
                .build();
    }

    /**
     * 链路二的降级模型（deepseek）。
     * <p>
     * 与链路一的主备正好互补：链路一主 deepseek、备百炼，链路二主百炼、备 deepseek，
     * 从而避免主备共享同一个账号/网关时同源故障导致降级失效。
     * <p>
     * base URL 从 {@link ChatProvider} 反推（剥掉 {@code /chat/completions} 后缀），
     * 复用其已有的归一化逻辑，避免再维护一个可能与 {@code deepseek.chat-url} 漂移的配置项。
     */
    @Bean("degradeChatModel")
    public ChatModel degradeChatModel(
            @Value("${deepseek.chat-url}") String chatUrl,
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.chat-model}") String modelName
    ) {
        String baseUrl = ChatProvider.of("deepseek", chatUrl, apiKey, modelName).baseUrl();
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(CHAT_TIMEOUT_SECONDS))
                .maxRetries(0)
                .build();
    }

    /**
     * 链路二的容错编排器：主模型 → 降级模型，共用链路一的重试/熔断策略数值。
     * <p>
     * 复用 {@code agent.llm.*} 而非新开一套配置，保证两条链路的容错口径只有一处可调。
     */
    @Bean
    public ChatModelFailoverOrchestrator chatModelFailoverOrchestrator(
            @Qualifier("chatModel") ChatModel chatModel,
            @Value("${bailian.chat-model}") String primaryModelName,
            @Qualifier("degradeChatModel") ChatModel degradeChatModel,
            @Value("${deepseek.chat-model}") String degradeModelName,
            @Value("${agent.llm.retry-attempts:2}") int retryAttempts,
            @Value("${agent.llm.retry-backoff-ms:500}") long retryBackoffMs,
            @Value("${agent.llm.circuit-failure-threshold:5}") int circuitFailureThreshold,
            @Value("${agent.llm.circuit-open-ms:30000}") long circuitOpenMs
    ) {
        List<NamedChatModel> models = List.of(
                new NamedChatModel("bailian/" + primaryModelName, chatModel),
                new NamedChatModel("deepseek/" + degradeModelName, degradeChatModel));
        return new ChatModelFailoverOrchestrator(models, SCENE_REACT,
                retryAttempts, retryBackoffMs, circuitFailureThreshold, circuitOpenMs);
    }

    /**
     * 评测 Judge 专用模型，与 Agent 主模型解耦，用于对 Agent 输出做六维质量打分。
     * <p>
     * 以独立的 Bean 名暴露，供 EvalJudgeService 按 @Qualifier("judgeChatModel") 精确注入；
     * temperature 固定 0.0 保证评分稳定可复现。模型名由 agent.eval.judge-model 指定。
     * <p>
     * 刻意不接入降级与熔断，也保留 langchain4j 内置重试：换模型会让评分尺度随故障漂移、
     * 污染评测结论，Judge 场景应快速失败而非降级。
     */
    @Bean("judgeChatModel")
    public ChatModel judgeChatModel(
            @Value("${bailian.chat-url}") String baseUrl,
            @Value("${bailian.api-key}") String apiKey,
            @Value("${agent.eval.judge-model:qwen-max}") String modelName,
            @Value("${agent.eval.judge-timeout-seconds:120}") long timeoutSeconds
    ) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }
}
