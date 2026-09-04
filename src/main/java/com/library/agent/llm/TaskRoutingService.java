package com.library.agent.llm;

import com.library.agent.context.AgentChatContext;
import com.library.agent.enums.AgentTask;
import com.library.agent.observability.ConversationTraceCollector;

import java.util.List;

/**
 * 第二层任务上下文的路由决议服务。
 * <p>
 * 承载"组装路由提示 → 调用 LLM → 校验任务名 → 纠错重试 → 无匹配回退"的完整流程，
 * 两侧聊天链路（同步/响应式）共用，避免重复实现与 Reactive 遗漏接入。
 */
public interface TaskRoutingService {

    /**
     * 决议当前问题应加载哪个第二层任务上下文（不记录可观测遥测）。
     */
    RouteResolution resolve(AgentChatContext context);

    /**
     * 决议当前问题应加载哪个第二层任务上下文，并把每次路由 LLM 调用记入采集器。
     */
    RouteResolution resolve(AgentChatContext context, ConversationTraceCollector collector);

    /**
     * 单次路由 LLM 调用的输入输出快照。
     *
     * @param prompt 实际发送给模型的路由提示（首轮或纠错轮）
     * @param result 模型原始输出
     */
    record RouteAttempt(String prompt, String result) {
    }

    /**
     * 路由决议结果。
     *
     * @param task 校验通过的第二层任务；无匹配时为空
     * @param noMatchMessage 无匹配时的回退文案（明确告知能力未覆盖并列出可用能力）
     * @param attempts 全部路由调用记录，用于测试与日志
     */
    record RouteResolution(AgentTask task, String noMatchMessage, List<RouteAttempt> attempts) {

        /**
         * 是否在可用任务清单内命中。
         */
        public boolean matched() {
            return task != null;
        }
    }
}
