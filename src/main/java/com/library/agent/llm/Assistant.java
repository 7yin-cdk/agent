package com.library.agent.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

@AiService( tools = "weatherTool")
public interface Assistant {

    @SystemMessage("你是一个智能助手，要求回答用户问题，可以使用工具")
    String chat(@UserMessage String message);
}
