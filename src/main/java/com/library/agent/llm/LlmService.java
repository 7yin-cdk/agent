package com.library.agent.llm;

public interface LlmService {

    /**
     * 将组装好的
     * @param prompt
     * @return
     */
    String chat(String prompt);
}
