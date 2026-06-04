package com.library.agent.llm;

import java.util.List;
import java.util.function.Consumer;

public interface LlmService {

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
     * 将多个文本转为向量（1024维）
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
}
