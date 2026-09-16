package com.library.agent.eval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.eval.dto.EvalJudgeRequest;
import com.library.agent.eval.dto.EvalJudgeResponse;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回答质量 LLM-as-Judge 打分服务。
 * <p>
 * 使用与 Agent 主模型解耦的 {@code judgeChatModel}（由 agent.eval.judge-model 指定，
 * temperature 固定 0.0）配合冻结版本的 rubric 提示词，对单条回答做六维打分并加权汇总。
 * rubric 模板与版本随批次一同记录，保证历史结果可复现。
 */
@Slf4j
@Service
public class EvalJudgeService {

    /**
     * rubric 提示词模板的 classpath 路径
     */
    private static final String RUBRIC_PATH = "Prompt/eval/JudgeRubricPrompt.md";

    /**
     * rubric 版本号；提示词改动时必须同步递增，否则历史批次分数不可比
     */
    public static final String RUBRIC_VERSION = "judge-rubric-v1";

    /**
     * 六维权重：正确性 / 完整性 / 可操作性 / 相关性 / 安全 / 格式
     */
    private static final Map<String, Double> WEIGHTS = Map.of(
            "correctness", 0.30,
            "completeness", 0.20,
            "actionability", 0.20,
            "relevance", 0.15,
            "safety", 0.10,
            "format", 0.05
    );

    /**
     * 六维的标准输出顺序（Map.of 无序，输出时按此顺序整理便于阅读）
     */
    private static final List<String> DIMENSIONS = List.of(
            "correctness", "completeness", "actionability", "relevance", "safety", "format");

    /**
     * 分值合法区间
     */
    private static final int MIN_SCORE = 1;
    private static final int MAX_SCORE = 5;

    private final ChatModel judgeChatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String rubricTemplate;

    public EvalJudgeService(@Qualifier("judgeChatModel") ChatModel judgeChatModel) {
        this.judgeChatModel = judgeChatModel;
        this.rubricTemplate = loadRubric();
    }

    /**
     * 对单条回答打分。
     *
     * @param request 含用户问题、Agent 回答与可选参考要点
     * @return 六维原始分、加权总分、评判理由与实际使用的模型
     * @throws ResponseStatusException query 为空时 400；Judge 输出无法解析时 502
     */
    public EvalJudgeResponse judge(EvalJudgeRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query 不能为空");
        }

        String prompt = buildPrompt(request);
        ChatResponse response = judgeChatModel.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from(prompt)))
                .build());

        String output = response.aiMessage() == null ? null : response.aiMessage().text();
        JudgeScores scores = parseScores(output);

        EvalJudgeResponse result = new EvalJudgeResponse();
        result.setScores(scores.dimensions);
        result.setTotal(weightedTotal(scores.dimensions));
        result.setReason(scores.reason);
        result.setModel(response.modelName());
        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            result.setInputTokens(usage.inputTokenCount());
            result.setOutputTokens(usage.outputTokenCount());
        }
        return result;
    }

    /**
     * 渲染 rubric 模板：填入问题、回答与参考要点。
     */
    private String buildPrompt(EvalJudgeRequest request) {
        String answer = request.getAnswer() == null || request.getAnswer().isBlank()
                ? "（Agent 未返回任何内容）" : request.getAnswer();
        String keyPoints = request.getKeyPoints() == null || request.getKeyPoints().isEmpty()
                ? "（无）" : String.join("\n", request.getKeyPoints());
        return rubricTemplate
                .replace("{{query}}", request.getQuery())
                .replace("{{answer}}", answer)
                .replace("{{key_points}}", keyPoints);
    }

    /**
     * 解析 Judge 输出的 JSON，容忍模型误加 Markdown 代码块。
     */
    private JudgeScores parseScores(String output) {
        String json = extractJson(output);
        if (json == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Judge 未返回可解析的 JSON");
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            Map<String, Object> dimensions = new LinkedHashMap<>();
            for (String dimension : DIMENSIONS) {
                JsonNode value = node.get(dimension);
                if (value == null || !value.isNumber()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Judge 输出缺少维度: " + dimension);
                }
                dimensions.put(dimension, clamp(value.asInt()));
            }
            String reason = node.path("reason").isTextual() ? node.get("reason").asText() : null;
            return new JudgeScores(dimensions, reason);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to parse judge output: {}", output, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Judge 输出解析失败");
        }
    }

    /**
     * 从模型输出中截取首个 JSON 对象（去掉可能存在的 ```json 包裹与前后噪声）。
     */
    private String extractJson(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        return (start < 0 || end <= start) ? null : output.substring(start, end + 1);
    }

    /**
     * 加权总分 = Σ(维度分 × 权重)，保留两位小数。
     */
    private BigDecimal weightedTotal(Map<String, Object> dimensions) {
        double total = 0;
        for (Map.Entry<String, Double> entry : WEIGHTS.entrySet()) {
            Object score = dimensions.get(entry.getKey());
            if (score instanceof Number number) {
                total += number.doubleValue() * entry.getValue();
            }
        }
        return BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 把越界分值夹取到合法区间，避免模型给出 0 或 6。
     */
    private int clamp(int score) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
    }

    /**
     * 从 classpath 读取 rubric 模板，读取失败直接抛异常阻断启动，避免用空提示词静默打分。
     */
    private String loadRubric() {
        try (InputStream input = new ClassPathResource(RUBRIC_PATH).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load judge rubric: " + RUBRIC_PATH, e);
        }
    }

    /**
     * Judge 解析结果的内部载体。
     */
    private record JudgeScores(Map<String, Object> dimensions, String reason) {
    }
}
