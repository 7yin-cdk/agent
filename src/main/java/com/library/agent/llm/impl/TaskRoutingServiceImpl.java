package com.library.agent.llm.impl;

import com.library.agent.context.AgentChatContext;
import com.library.agent.enums.AgentTask;
import com.library.agent.llm.LlmService;
import com.library.agent.llm.PromptBuilder;
import com.library.agent.llm.TaskRoutingService;
import com.library.agent.observability.ConversationTraceCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 第二层任务上下文路由决议实现。
 * <p>
 * 先构建"第一层"路由提示（含会话摘要/历史/用户问题），调用路由 LLM 选择任务；
 * 对输出做两层校验（PromptBuilder.extractRouteTask 语法解析 + AgentTask.fromRouteName 语义校验）。
 * 未命中时把"只能从合法清单中选"拼回提示再让 LLM 重选，直至达到最大调用次数；
 * 全部失败则返回"无匹配能力"回退文案，不执行、不猜测。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRoutingServiceImpl implements TaskRoutingService {

    private static final String LLM_MODEL_NAME = "deepseek";

    private final LlmService llmService;

    /**
     * 路由阶段 LLM 总调用次数（含首次）。默认 2，即最多纠错重试一次。
     */
    @Value("${agent.task.max-route-attempts:2}")
    private int maxRouteAttempts;

    @Override
    public RouteResolution resolve(AgentChatContext context) {
        return resolve(context, null);
    }

    @Override
    public RouteResolution resolve(AgentChatContext context, ConversationTraceCollector collector) {
        if (context == null) {
            return new RouteResolution(null, buildNoMatchMessage(), List.of());
        }

        String basePrompt = PromptBuilder.buildRoutePrompt(
                context.getQuery(),
                context.getConversationSummary(),
                context.getHistoryMessages()
        );

        int attempts = Math.max(1, maxRouteAttempts);
        List<RouteAttempt> routeAttempts = new ArrayList<>();
        Optional<String> lastExtractedName = Optional.empty();

        for (int attempt = 0; attempt < attempts; attempt++) {
            String prompt = attempt == 0
                    ? basePrompt
                    : basePrompt + buildCorrectionSuffix(lastExtractedName);

            String result = chatWithTrace(prompt, attempt == 0, collector);
            routeAttempts.add(new RouteAttempt(prompt, result));

            lastExtractedName = PromptBuilder.extractRouteTask(result);
            Optional<AgentTask> matched = lastExtractedName.flatMap(AgentTask::fromRouteName);
            if (matched.isPresent()) {
                log.info("Route resolved task={} attempts={}", matched.get().routeName(), attempt + 1);
                return new RouteResolution(matched.get(), null, routeAttempts);
            }
        }

        log.warn("Route failed after {} attempts, last output={}", attempts,
                routeAttempts.isEmpty() ? "N/A" : routeAttempts.get(routeAttempts.size() - 1).result());
        return new RouteResolution(null, buildNoMatchMessage(), routeAttempts);
    }

    /**
     * 调用路由 LLM 并在有采集器时记录本次遥测（首次 ROUTE、纠错 ROUTE_RETRY）。
     */
    private String chatWithTrace(String prompt, boolean firstAttempt, ConversationTraceCollector collector) {
        long start = System.currentTimeMillis();
        String result = llmService.chat(prompt);
        long duration = System.currentTimeMillis() - start;

        if (collector != null) {
            LlmService.TokenUsage usage = llmService.getLastTokenUsage();
            collector.recordLlmCall(LLM_MODEL_NAME,
                    firstAttempt ? "ROUTE" : "ROUTE_RETRY",
                    prompt, result,
                    usage != null ? usage.getInputTokens() : 0,
                    usage != null ? usage.getOutputTokens() : 0,
                    duration);
            llmService.clearLastTokenUsage();
        }
        return result;
    }

    /**
     * 纠错后缀：指出上一轮输出无效并硬性限定可选任务名。
     *
     * @param lastExtractedName 上一轮解析出的任务名；为空表示未能解析出有效 task
     */
    private String buildCorrectionSuffix(Optional<String> lastExtractedName) {
        String invalidDesc = lastExtractedName
                .map(name -> "解析到的任务名 \"" + name + "\" 不在可用清单中")
                .orElse("未能从你的输出中解析出有效的 task 字段");
        return "\n\n=== 上一轮路由无效，请重新选择 ===\n"
                + "你上一轮的选择无效（" + invalidDesc + "）。忽略你此前的输出，重新从以下任务名中选择一个：\n"
                + AgentTask.routeNamesText() + "\n"
                + "要求：task 值必须与上述任务名完全一致（大小写敏感），禁止输出清单之外的任何新任务名。\n"
                + "只输出 JSON：{\"task\":\"<任务名>\"}，不要输出任何其他内容。";
    }

    /**
     * "无匹配能力"回退文案：明确告知未命中，并列出可用能力供用户换一种描述。
     */
    private String buildNoMatchMessage() {
        return "抱歉，我未能从现有能力中匹配到可执行模块来完成你的请求，因此没有执行任何工具或数据库操作。\n"
                + "你可从以下能力中选择更贴合的一项并重新描述：\n"
                + AgentTask.availableTasksText();
    }
}
