package com.library.agent.service.Impl;

import com.library.agent.conversation.service.ConversationService;
import com.library.agent.enums.IntentType;
import com.library.agent.llm.LlmService;
import com.library.agent.llm.QueryRewriteService;
import com.library.agent.llm.ToolCallingService;
import com.library.agent.memory.ConversationSummaryService;
import com.library.agent.memory.ShortTermMemoryService;
import com.library.agent.rag.service.RagService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentServiceImplIntentTest {

    @Test
    void identifiesInternalPolicyQuestionAsKnowledgeBase() {
        LlmService llmService = mock(LlmService.class);
        AgentServiceImpl agentService = newAgentService(llmService);

        IntentType intentType = agentService.identifyIntent("公司报销制度怎么规定？", List.of());

        assertThat(intentType).isEqualTo(IntentType.KNOWLEDGE_BASE);
        verifyNoInteractions(llmService);
    }

    @Test
    void identifiesInternalLookupQuestionAsKnowledgeBaseInsteadOfToolCall() {
        LlmService llmService = mock(LlmService.class);
        AgentServiceImpl agentService = newAgentService(llmService);

        IntentType intentType = agentService.identifyIntent("帮我查公司年假规定", List.of());

        assertThat(intentType).isEqualTo(IntentType.KNOWLEDGE_BASE);
        verifyNoInteractions(llmService);
    }

    @Test
    void genericRuleQuestionDoesNotHitKnowledgeBaseKeywordRule() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString())).thenReturn("SIMPLE_CHAT");
        AgentServiceImpl agentService = newAgentService(llmService);

        IntentType intentType = agentService.identifyIntent("交通规则有哪些？", List.of());

        assertThat(intentType).isEqualTo(IntentType.SIMPLE_CHAT);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmService).chat(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("只有当用户问题明确指向公司内部制度")
                .contains("不要因为问题里出现“文档、资料、制度、规则、规定、文件、手册”等词就直接选择 KNOWLEDGE_BASE");
    }

    @Test
    void programmingFileQuestionDoesNotHitKnowledgeBaseKeywordRule() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString())).thenReturn("SIMPLE_CHAT");
        AgentServiceImpl agentService = newAgentService(llmService);

        IntentType intentType = agentService.identifyIntent("Java 中怎么读取文件？", List.of());

        assertThat(intentType).isEqualTo(IntentType.SIMPLE_CHAT);
        verify(llmService).chat(anyString());
    }

    private AgentServiceImpl newAgentService(LlmService llmService) {
        return new AgentServiceImpl(
                llmService,
                mock(RagService.class),
                mock(ConversationService.class),
                mock(ShortTermMemoryService.class),
                mock(ConversationSummaryService.class),
                mock(ToolCallingService.class),
                mock(QueryRewriteService.class)
        );
    }
}
