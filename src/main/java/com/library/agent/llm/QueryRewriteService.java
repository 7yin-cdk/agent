package com.library.agent.llm;

import com.library.agent.entity.AgentShortTermMemory;

import java.util.List;

public interface QueryRewriteService {
    /**
     * 查询重写
     * @param query 用户原始提问
     * @param conversationSummary 会话摘要
     * @param historyMessages 当前会话历史消息
     * @return
     */
    QueryRewriteResult rewrite(
            String query,
            String conversationSummary,
            List<AgentShortTermMemory> historyMessages
    );
}
