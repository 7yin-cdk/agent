package com.library.agent.eval;

import com.library.agent.eval.dto.EvalJudgeRequest;
import com.library.agent.eval.dto.EvalJudgeToolCall;
import com.library.agent.eval.service.EvalJudgeService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EvalJudgeService 的提示词渲染测试（不调用真实 Judge 模型）。
 * <p>
 * 覆盖点：Agent 的工具调用序列必须真正进入 rubric 提示词。缺少该段时，Judge 只能凭回答正文
 * 推断调用与否，会把"先下钻取证再作答"误判成"未调用工具"，系统性压低诊断类用例的分数。
 */
class EvalJudgeServicePromptTest {

    /**
     * Judge 输出的合法打分 JSON（解析通过即可，分值本身不参与断言）。
     */
    private static final String JUDGE_OUTPUT = """
            {"correctness":5,"completeness":5,"actionability":5,"relevance":5,"safety":5,"format":5,"reason":"ok"}
            """;

    private ChatModel judgeModelMock;

    /**
     * 有工具调用时：工具名、入参、执行结果都要落到提示词里，且占位符被替换掉。
     */
    @Test
    void toolCallsAreRenderedIntoJudgePrompt() {
        EvalJudgeRequest request = new EvalJudgeRequest();
        request.setQuery("rag库 现在有锁阻塞吗？");
        request.setAnswer("当前没有检出锁阻塞。");
        request.setKeyPoints(List.of("应调用锁阻塞下钻工具"));
        request.setToolCalls(List.of(toolCall("getBlockingChains", "{\"instance\":\"rag库\"}", true)));

        EvalJudgeService service = buildService();

        service.judge(request);

        String prompt = capturedPrompt();
        assertTrue(prompt.contains("1. getBlockingChains | 入参: {\"instance\":\"rag库\"} | 结果: 成功"),
                "工具调用应逐行渲染进提示词，实际: " + prompt);
        assertFalse(prompt.contains("{{tool_calls}}"), "占位符必须被替换，实际: " + prompt);
        assertTrue(prompt.contains("以工具调用清单为准"), "rubric 应声明以清单为准而非凭正文推断");
    }

    /**
     * 无工具调用时给出显式说明，而不是留空白让 Judge 自行猜测。
     */
    @Test
    void emptyToolCallsAreStatedExplicitly() {
        EvalJudgeRequest request = new EvalJudgeRequest();
        request.setQuery("你好");
        request.setAnswer("你好，我可以帮你做数据库巡检与诊断。");

        EvalJudgeService service = buildService();

        service.judge(request);

        String prompt = capturedPrompt();
        assertTrue(prompt.contains("（本次回答未经过任何工具调用）"),
                "无调用时应显式说明，实际: " + prompt);
    }

    private EvalJudgeToolCall toolCall(String name, String input, boolean success) {
        EvalJudgeToolCall call = new EvalJudgeToolCall();
        call.setToolName(name);
        call.setToolInput(input);
        call.setSuccess(success);
        return call;
    }

    private EvalJudgeService buildService() {
        judgeModelMock = mock(ChatModel.class);
        when(judgeModelMock.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(JUDGE_OUTPUT))
                .modelName("judge-stub")
                .build());
        return new EvalJudgeService(judgeModelMock);
    }

    private String capturedPrompt() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(judgeModelMock).chat(captor.capture());
        List<ChatMessage> messages = captor.getValue().messages();
        return ((UserMessage) messages.get(messages.size() - 1)).singleText();
    }
}
