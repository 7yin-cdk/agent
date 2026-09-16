package com.library.agent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
public class LangChain4jConfig {

    /**
     * 主对话模型，供 Agent 工具编排（ReAct）与路由使用。
     * <p>
     * 标注 @Primary：引入评测用 judgeChatModel 后容器内存在多个 ChatModel Bean，
     * 若未指定默认候选，按类型注入（如 ToolCallingServiceImpl）会抛
     * NoUniqueBeanDefinitionException。
     */
    @Bean
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
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * 评测 Judge 专用模型，与 Agent 主模型解耦，用于对 Agent 输出做六维质量打分。
     * <p>
     * 以独立的 Bean 名暴露，供 EvalJudgeService 按 @Qualifier("judgeChatModel") 精确注入；
     * temperature 固定 0.0 保证评分稳定可复现。模型名由 agent.eval.judge-model 指定。
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
