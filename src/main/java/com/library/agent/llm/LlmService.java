package com.library.agent.llm;

import java.util.List;
import java.util.function.Consumer;

public interface LlmService {

    /**
     * 单次 LLM 调用的 Token 用量统计。
     */
    class TokenUsage {
        private final int inputTokens;
        private final int outputTokens;
        private final int totalTokens;

        public TokenUsage(int inputTokens, int outputTokens, int totalTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.totalTokens = totalTokens;
        }

        public int getInputTokens() { return inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public int getTotalTokens() { return totalTokens; }
    }

    /**
     * 用户提问与大模型交互
     * @param prompt 组装后的prompt
     * @return
     */
    String chat(String prompt);

    /**
     * Stream chat response tokens for a prompt.
     *
     * @param prompt assembled prompt
     * @param onDelta token callback
     */
    void chatStream(String prompt, Consumer<String> onDelta);

    /**
     * 将多个文本转为向量（1536维）
     * @param texts 输入文本集合
     * @return 向量结果
     */

    List<List<Float>> embed(List<String> texts);

    /**
     * 将单个文本转为向量（1024维）
     * @param text 输入文本
     * @return 向量结果
     */
    List<Float> embed(String text);

//    /**
//     * 与具有工具调用功能的大模型交互
//     * @param prompt
//     * @return
//     */
//    String toolChat(String prompt);

    /**
     * 对召回到的文本重排序
     * @param query 用户提问
     * @param documents 多路召回的文本
     * @param topN 最终保留的文本数
     * @return 文档原始下标
     */
    List<Integer> rerank(String query, List<String> documents, int topN, double minScore);

    /**
     * 获取最后一次 LLM 调用的 Token 用量。
     * <p>
     * 在 chat() 或 chatStream() 调用后通过 ThreadLocal 获取，
     * 调用 clearLastTokenUsage() 清理。
     */
    TokenUsage getLastTokenUsage();

    /**
     * 清理最后一次 LLM 调用的 Token 用量记录。
     */
    void clearLastTokenUsage();
}
