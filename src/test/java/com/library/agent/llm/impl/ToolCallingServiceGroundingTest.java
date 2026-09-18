package com.library.agent.llm.impl;

import com.library.agent.context.AgentChatContext;
import com.library.agent.enums.IntentType;
import com.library.agent.llm.LlmExhaustedException;
import com.library.agent.llm.resilience.ChatModelFailoverOrchestrator;
import com.library.agent.llm.resilience.ChatModelFailoverOrchestrator.NamedChatModel;
import com.library.agent.tool.SlowQueryTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * <p>
 * 拦截后的契约：纠正文案只作为失败 observation 回灌给模型（由模型自行重试或向用户追问），
 * 绝不直接充当最终回答——否则面向模型的内部措辞会泄露给用户。
 */
class ToolCallingServiceGroundingTest {

    private ChatModel chatModelMock;

    /**
     * 读工具（getTopSlowQueries）指代候选 >= 2 且未落字 → 拦截，纠正文案回灌模型，候选随之进 prompt。
     * <p>
     * instance 未在本轮原话中，来源为 REFERENCED_CURRENT、候选有 2 个 → 歧义拦截；
     * database 在本轮原话中字面出现（EXPLICIT_CURRENT）→ 放行。
     * 模型收到纠正后改用 finish 向用户追问，最终回答应为模型的追问原文。
     */
    @Test
    void readToolAmbiguousCandidatesAreBlockedAndAskListsAllCandidates() {
        String ambiguousOutput = """
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
        String askOutput = """
                {
                  "type": "finish",
                  "thought": "实例指代有歧义，改向用户追问",
                  "tool": null,
                  "finish": {"answer": "请确认要查哪个实例：192.168.1.100:5432 还是 10.0.0.5:5432？"}
                }
                """;

        ToolCallingServiceImpl service = buildServiceWithSlowQueryTool(ambiguousOutput, askOutput);

        AgentChatContext context = new AgentChatContext();
        context.setConversationId("conv-ambiguous");
        context.setIntentType(IntentType.COMPLEX_TASK);
        /* database 已字面提供并落字；instance 未在原话中出现（靠指代 + 2 个候选） */
        context.setQuery("查一下 test_db 库的慢查询有哪些");

        String answer = service.chatWithTasks(context, "慢查询任务，含 {{react_history}}");

        /* 最终回答来自模型的 finish，而不是校验器拼出来的内部措辞 */
        assertTrue(answer.contains("请确认要查哪个实例"), "最终回答应为模型的追问，实际: " + answer);
        assertFalse(answer.contains("参数来源标注"), "内部校验措辞不得出现在最终回答，实际: " + answer);

        /* 纠正文案确实回灌给了模型：第二轮 prompt 里能看到全部候选对象 */
        String secondPrompt = capturedPrompt(2);
        assertTrue(secondPrompt.contains("192.168.1.100:5432"), "纠正应列出候选1，实际: " + secondPrompt);
        assertTrue(secondPrompt.contains("10.0.0.5:5432"), "纠正应列出候选2，实际: " + secondPrompt);
        /* 已落字的 database 不应被追问 */
        assertFalse(secondPrompt.contains("「database」"), "已落字参数不应被追问，实际: " + secondPrompt);

        /* 拦截发生在工具执行前：工具输出从未进入对话 */
        assertFalse(answer.contains("SlowQuery"), "工具不应被执行，实际: " + answer);
        verify(chatModelMock, times(2)).chat(any(ChatRequest.class));
    }

    /**
     * 模型反复输出同一个被拦截的动作、始终不自纠错 → 到阈值后返回面向用户的兜底文案。
     * <p>
     * 兜底文案不得包含校验器的内部措辞，且必须可被评测侧识别为系统兜底
     * （{@code tsr.py} 的 FAILURE_MARKERS 收有「本次请求缺少完成任务所必需的参数信息」）。
     */
    @Test
    void repeatedGuardRejectionFallsBackToUserFacingMessage() {
        String ambiguousOutput = """
                {
                  "type": "tool",
                  "thought": "仍然按指代猜测实例",
                  "tool": {
                    "name": "getTopSlowQueries",
                    "arguments": {"instance": "192.168.1.100:5432", "database": "test_db"},
                    "argument_sources": {"instance": "REFERENCED_CURRENT", "database": "EXPLICIT_CURRENT"},
                    "argument_candidates": {"instance": ["192.168.1.100:5432", "10.0.0.5:5432"]}
                  },
                  "finish": null
                }
                """;

        ToolCallingServiceImpl service = buildServiceWithSlowQueryTool(ambiguousOutput);

        AgentChatContext context = new AgentChatContext();
        context.setConversationId("conv-ambiguous-loop");
        context.setIntentType(IntentType.COMPLEX_TASK);
        context.setQuery("查一下 test_db 库的慢查询有哪些");

        String answer = service.chatWithTasks(context, "慢查询任务，含 {{react_history}}");

        assertTrue(answer.contains("本次请求缺少完成任务所必需的参数信息"),
                "连续被拦截应返回面向用户的兜底文案，实际: " + answer);
        assertFalse(answer.contains("参数来源标注"), "兜底文案不得泄露内部措辞，实际: " + answer);
        assertFalse(answer.contains("argument_sources"), "兜底文案不得泄露内部字段名，实际: " + answer);
        verify(chatModelMock, times(3)).chat(any(ChatRequest.class));
    }

    /* ======================= TOOL_OUTPUT 链式下钻 ======================= */

    /**
     * 第一步工具成功返回含 database 的结果，第二步以 TOOL_OUTPUT 复用该值 → 放行并执行。
     */
    @Test
    void toolOutputChainedCallIsAllowedAfterSuccessfulToolRun() {
        String collectOutput = """
                {
                  "type": "tool",
                  "thought": "先取该实例的概览",
                  "tool": {
                    "name": "fakeCollectOverview",
                    "arguments": {"instance": "rag库"},
                    "argument_sources": {"instance": "EXPLICIT_CURRENT"},
                    "argument_candidates": {}
                  },
                  "finish": null
                }
                """;
        String drillOutput = """
                {
                  "type": "tool",
                  "thought": "复用上一步的实例与库名继续下钻",
                  "tool": {
                    "name": "fakeDrillDown",
                    "arguments": {"instance": "rag库", "database": "rag_db"},
                    "argument_sources": {"instance": "TOOL_OUTPUT", "database": "TOOL_OUTPUT"},
                    "argument_candidates": {}
                  },
                  "finish": null
                }
                """;
        String finishOutput = """
                {
                  "type": "finish",
                  "thought": "已拿到证据",
                  "tool": null,
                  "finish": {"answer": "DONE 已定位根因"}
                }
                """;

        ToolCallingServiceImpl service = buildServiceWithFakeTools(
                collectOutput, drillOutput, finishOutput);

        AgentChatContext context = new AgentChatContext();
        context.setConversationId("conv-chain-ok");
        context.setIntentType(IntentType.COMPLEX_TASK);
        /* 业务名 rag库 字面出现 → 第一步 instance 落字放行 */
        context.setQuery("排查 rag库 现在卡在哪");

        String answer = service.chatWithTasks(context, "诊断任务，含 {{react_history}}");

        assertTrue(answer.contains("DONE"), "链式下钻应被执行并走到 finish，实际: " + answer);
        verify(chatModelMock, times(3)).chat(any(ChatRequest.class));
    }

    /**
     * 反向孪生用例：第二步的 database 不在上一步工具输出中 → 拦截，纠正回灌模型后由模型追问。
     */
    @Test
    void toolOutputChainedCallIsBlockedWhenValueNotInPriorOutput() {
        String collectOutput = """
                {
                  "type": "tool",
                  "thought": "先取该实例的概览",
                  "tool": {
                    "name": "fakeCollectOverview",
                    "arguments": {"instance": "rag库"},
                    "argument_sources": {"instance": "EXPLICIT_CURRENT"},
                    "argument_candidates": {}
                  },
                  "finish": null
                }
                """;
        String drillOutput = """
                {
                  "type": "tool",
                  "thought": "复用上一步的值继续下钻",
                  "tool": {
                    "name": "fakeDrillDown",
                    "arguments": {"instance": "rag库", "database": "other_db"},
                    "argument_sources": {"instance": "TOOL_OUTPUT", "database": "TOOL_OUTPUT"},
                    "argument_candidates": {}
                  },
                  "finish": null
                }
                """;
        String askOutput = """
                {
                  "type": "finish",
                  "thought": "上一步输出里没有该库名，改向用户追问",
                  "tool": null,
                  "finish": {"answer": "请问要下钻的库名是什么？"}
                }
                """;

        ToolCallingServiceImpl service =
                buildServiceWithFakeTools(collectOutput, drillOutput, askOutput);

        AgentChatContext context = new AgentChatContext();
        context.setConversationId("conv-chain-block");
        context.setIntentType(IntentType.COMPLEX_TASK);
        context.setQuery("排查 rag库 现在卡在哪");

        String answer = service.chatWithTasks(context, "诊断任务，含 {{react_history}}");

        assertTrue(answer.contains("请问要下钻的库名"), "最终回答应为模型的追问，实际: " + answer);
        /* 纠正回灌给了模型：第三轮 prompt 点名未落字的 database 参数 */
        assertTrue(capturedPrompt(3).contains("参数「database」"),
                "纠正应点名未落字的 database 参数，实际: " + capturedPrompt(3));
        /* 第一步执行、第二步被拦、第三步 finish：共 3 次 LLM 调用，下钻工具从未执行 */
        assertFalse(answer.contains("DRILL_OK"), "被拦截的下钻不应执行，实际: " + answer);
        verify(chatModelMock, times(3)).chat(any(ChatRequest.class));
    }

    /**
     * 写工具使用 TOOL_OUTPUT 且本轮未字面提供 → 执行前被拦截（fail-closed），纠正回灌后由模型追问。
     */
    @Test
    void toolOutputSourceIsBlockedBeforeWriteToolExecution() {
        String writeOutput = """
                {
                  "type": "tool",
                  "thought": "重置统计",
                  "tool": {
                    "name": "resetSlowQueryStats",
                    "arguments": {"instance": "192.168.1.100:5432", "database": "test_db"},
                    "argument_sources": {"instance": "TOOL_OUTPUT", "database": "TOOL_OUTPUT"},
                    "argument_candidates": {}
                  },
                  "finish": null
                }
                """;
        String askOutput = """
                {
                  "type": "finish",
                  "thought": "写操作不能按推测放行，改向用户确认实例",
                  "tool": null,
                  "finish": {"answer": "请提供要重置统计的实例地址（host:port）。"}
                }
                """;

        ToolCallingServiceImpl service = buildServiceWithSlowQueryTool(writeOutput, askOutput);

        AgentChatContext context = new AgentChatContext();
        context.setConversationId("conv-write-tool-output");
        context.setIntentType(IntentType.COMPLEX_TASK);
        /* 本轮原话不含字面值，因此写工具不得按 TOOL_OUTPUT 放行 */
        context.setQuery("把那个库的慢查询统计重置掉");

        String answer = service.chatWithTasks(context, "慢查询任务，含 {{react_history}}");

        assertTrue(answer.contains("请提供要重置统计的实例地址"), "最终回答应为模型的追问，实际: " + answer);
        assertTrue(capturedPrompt(2).contains("参数「instance」"),
                "纠正应点名 instance 参数，实际: " + capturedPrompt(2));
        assertFalse(answer.contains("参数「instance」"), "内部纠正文案不得作为最终回答，实际: " + answer);
        verify(chatModelMock, times(2)).chat(any(ChatRequest.class));
    }

    /**
     * 模型网关持续 5xx（可重试故障）且无备用模型可降级 → 立即返回统一友好文案。
     * <p>
     * 回归点：原先整段 step 被 catch(Exception) 吞掉，会把 10 步预算全部烧光，
     * 最后返回 "Too many ReAct steps. Please provide clearer conditions and try again."，
     * 把网关故障误导成"用户描述不清"。现在应在首次失败即短路，且调用次数为 1。
     */
    @Test
    void llmExhaustedShortCircuitsInsteadOfBurningReActSteps() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        Map<String, Object> beans = new HashMap<>();
        beans.put("slowQueryTool", new SlowQueryTool());
        when(applicationContext.getBeansOfType(Object.class, false, false)).thenReturn(beans);

        ChatModel failingModel = mock(ChatModel.class);
        when(failingModel.chat(any(ChatRequest.class)))
                .thenThrow(new InternalServerException("gateway unavailable"));

        ToolCallingServiceImpl service = new ToolCallingServiceImpl(
                singleModelOrchestrator(failingModel), applicationContext);
        setToolTimeoutSeconds(service, 30);
        service.registerTools();

        AgentChatContext context = new AgentChatContext();
        context.setConversationId("conv-llm-exhausted");
        context.setIntentType(IntentType.COMPLEX_TASK);
        context.setQuery("看下慢查询");

        String answer = service.chatWithTasks(context, "慢查询任务，含 {{react_history}}");

        assertEquals(LlmExhaustedException.USER_FACING_MESSAGE, answer,
                "模型全不可用时应返回统一友好文案，实际: " + answer);
        assertFalse(answer.contains("Too many ReAct steps"),
                "不应再返回把故障归咎于用户的 ReAct 步数耗尽提示，实际: " + answer);
        verify(failingModel, times(1)).chat(any(ChatRequest.class));
    }

    /* ======================= 装配辅助 ======================= */

    /**
     * 装配含真实只读工具（getTopSlowQueries / resetSlowQueryStats）的服务。
     *
     * @param modelOutputs 按调用次序脚本化的 LLM 输出；仅给一条时该输出会被反复复用
     */
    private ToolCallingServiceImpl buildServiceWithSlowQueryTool(String... modelOutputs) {
        /* 让 registerTools 扫描到一个真实的只读工具（getTopSlowQueries），校验必填参数 instance/database */
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        Map<String, Object> beans = new HashMap<>();
        beans.put("slowQueryTool", new SlowQueryTool());
        when(applicationContext.getBeansOfType(Object.class, false, false)).thenReturn(beans);

        return buildService(applicationContext, modelOutputs);
    }

    /**
     * 装配含两个假工具的链路：概览工具返回含 database 的 JSON，下钻工具只回显入参。
     * <p>
     * 用假工具是为了在不连真实数据库的前提下验证"上一步输出 → 下一步参数"的校验通路。
     *
     * @param modelOutputs 按调用次序脚本化的 LLM 输出
     */
    private ToolCallingServiceImpl buildServiceWithFakeTools(String... modelOutputs) {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        Map<String, Object> beans = new HashMap<>();
        beans.put("fakeDiagnosisTool", new FakeDiagnosisTool());
        when(applicationContext.getBeansOfType(Object.class, false, false)).thenReturn(beans);

        return buildService(applicationContext, modelOutputs);
    }

    private ToolCallingServiceImpl buildService(ApplicationContext applicationContext,
                                                String... modelOutputs) {
        chatModelMock = mock(ChatModel.class);
        ChatResponse[] responses = new ChatResponse[modelOutputs.length];
        for (int i = 0; i < modelOutputs.length; i++) {
            responses[i] = ChatResponse.builder().aiMessage(AiMessage.from(modelOutputs[i])).build();
        }
        when(chatModelMock.chat(any(ChatRequest.class))).thenReturn(responses[0],
                java.util.Arrays.copyOfRange(responses, 1, responses.length));

        ToolCallingServiceImpl service =
                new ToolCallingServiceImpl(singleModelOrchestrator(chatModelMock), applicationContext);
        /* toolTimeoutSeconds 由 @Value 注入，单测中未经过 Spring，需手工补齐，否则工具会被 0 秒超时打断 */
        setToolTimeoutSeconds(service, 30);
        service.registerTools();
        return service;
    }

    /**
     * 把单模型包一层容错编排器：不重试、熔断阈值拉满。
     * <p>
     * 本类用例聚焦 ReAct 校验逻辑，容错行为（重试/降级/熔断）由
     * {@code ChatModelFailoverOrchestratorTest} 单独覆盖，此处需保证其不干扰断言中的调用次数。
     */
    private static ChatModelFailoverOrchestrator singleModelOrchestrator(ChatModel model) {
        return new ChatModelFailoverOrchestrator(
                List.of(new NamedChatModel("bailian/qwen-plus", model)), "react",
                0, 0, Integer.MAX_VALUE, 10_000);
    }

    /**
     * 取第 callIndex 次（从 1 起）LLM 调用实际收到的 prompt 文本。
     * <p>
     * ReAct 的纠正是否真的回灌给模型，只能看下一轮 prompt，因此按调用次序捕获 ChatRequest。
     *
     * @param callIndex LLM 调用序号，从 1 开始
     * @return 该次调用的 prompt 原文
     */
    private String capturedPrompt(int callIndex) {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModelMock, times(callIndex)).chat(captor.capture());
        List<ChatRequest> requests = captor.getAllValues();
        ChatRequest request = requests.get(callIndex - 1);
        ChatMessage last = request.messages().get(request.messages().size() - 1);
        return ((UserMessage) last).singleText();
    }

    /**
     * 通过反射设置工具执行超时秒数（生产环境由 @Value 注入）。
     */
    private void setToolTimeoutSeconds(ToolCallingServiceImpl service, int seconds) {
        try {
            java.lang.reflect.Field field =
                    ToolCallingServiceImpl.class.getDeclaredField("toolTimeoutSeconds");
            field.setAccessible(true);
            field.setInt(service, seconds);
        } catch (Exception e) {
            throw new IllegalStateException("无法设置 toolTimeoutSeconds", e);
        }
    }

    /**
     * 测试专用假工具：不访问数据库，仅用于验证参数校验通路。
     */
    public static class FakeDiagnosisTool {

        /**
         * 模拟第一步概览工具，返回含 instance 与 database 的 JSON。
         */
        @Tool("测试用：返回含实例与库名的概览结果")
        public String fakeCollectOverview(
                @P("数据库实例地址 host:port，或巡检配置中的业务名") String instance) {
            return "{\"success\":true,\"instance\":\"" + instance + "\",\"database\":\"rag_db\"}";
        }

        /**
         * 模拟第二步下钻工具，只回显入参以证明参数确实传到了执行层。
         */
        @Tool("测试用：回显传入的实例与库名")
        public String fakeDrillDown(
                @P("数据库实例地址 host:port，或巡检配置中的业务名") String instance,
                @P("数据库名称") String database) {
            return "DRILL_OK instance=" + instance + " database=" + database;
        }
    }
}
