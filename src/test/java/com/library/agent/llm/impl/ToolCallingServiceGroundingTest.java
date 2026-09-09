package com.library.agent.llm.impl;

import com.library.agent.context.AgentChatContext;
import com.library.agent.enums.IntentType;
import com.library.agent.tool.SlowQueryTool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ToolCallingServiceImpl 严格落字校验的链路级测试（不走真实 LLM/DB）。
 * <p>
 * 通过注入固定返回值的 ChatModel，验证带 argument_candidates 的 ReAct 输出在
 * {@code parseDecision → validateToolAction → 聚合追问} 全链路上行为正确。
 * 本用例只覆盖“工具被拦截”的路径（拦截发生在执行前），因此无需真实数据库。
 */
class ToolCallingServiceGroundingTest {

    private ChatModel chatModelMock;

    /**
     * 读工具（getTopSlowQueries）指代候选 >= 2 且未落字 → 追问并列出候选，且不执行工具。
     * <p>
     * instance 未在本轮原话中，来源为 REFERENCED_CURRENT、候选有 2 个 → 歧义拦截；
     * database 在本轮原话中字面出现（EXPLICIT_CURRENT）→ 放行。
     */
    @Test
    void readToolAmbiguousCandidatesAreBlockedAndAskListsAllCandidates() {
        String modelOutput = """
                {
                  "type": "tool",
                  "thought": "用户指代查询某个实例的慢查询",
                  "tool": {
                    "name": "getTopSlowQueries",
                    "arguments": {
                      "instance": "192.168.1.100:5432",
                      "database": "test_db"
                    },
                    "argument_sources": {
                      "instance": "REFERENCED_CURRENT",
                      "database": "EXPLICIT_CURRENT"
                    },
                    "argument_candidates": {
                      "instance": ["192.168.1.100:5432", "10.0.0.5:5432"],
                      "database": []
                    }
                  },
                  "finish": null
                }
                """;

        ToolCallingServiceImpl service = buildServiceWithSlowQueryTool(modelOutput);

        AgentChatContext context = new AgentChatContext();
        context.setConversationId("conv-ambiguous");
        context.setIntentType(IntentType.COMPLEX_TASK);
        /* database 已字面提供并落字；instance 未在原话中出现（靠指代 + 2 个候选） */
        context.setQuery("查一下 test_db 库的慢查询有哪些");

        String answer = service.chatWithTasks(context, "慢查询任务，含 {{react_history}}");

        /* 命中歧义拦截：追问中列出全部候选对象 */
        assertTrue(answer.contains("instance"), "追问应点名歧义参数，实际: " + answer);
        assertTrue(answer.contains("192.168.1.100:5432"), "应列出候选1，实际: " + answer);
        assertTrue(answer.contains("10.0.0.5:5432"), "应列出候选2，实际: " + answer);
        /* 已落字的 database 不应被追问 */
        assertTrue(!answer.contains("database"), "已落字参数不应被追问，实际: " + answer);
        /* 校验拦截发生在工具执行前：LLM 仅被调用一次，未进入执行/失败重试 */
        verify(chatModelMock, times(1)).chat(any(ChatRequest.class));
    }

    /* ======================= 装配辅助 ======================= */

    private ToolCallingServiceImpl buildServiceWithSlowQueryTool(String modelOutput) {
        chatModelMock = mock(ChatModel.class);
        when(chatModelMock.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(modelOutput)).build());

        /* 让 registerTools 扫描到一个真实的只读工具（getTopSlowQueries），校验必填参数 instance/database */
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        Map<String, Object> beans = new HashMap<>();
        beans.put("slowQueryTool", new SlowQueryTool());
        when(applicationContext.getBeansOfType(Object.class, false, false)).thenReturn(beans);

        ToolCallingServiceImpl service = new ToolCallingServiceImpl(chatModelMock, applicationContext);
        service.registerTools();
        return service;
    }
}
