package com.library.agent.eval.service.impl;

import com.library.agent.entity.EvalCaseResult;
import com.library.agent.entity.EvalRun;
import com.library.agent.eval.dto.EvalCaseResultRequest;
import com.library.agent.eval.dto.EvalRunCreateRequest;
import com.library.agent.eval.dto.EvalRunView;
import com.library.agent.eval.service.EvalService;
import com.library.agent.eval.util.Percentiles;
import com.library.agent.mapper.EvalCaseResultMapper;
import com.library.agent.mapper.EvalRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 测评批次服务实现。
 * <p>
 * 批次级聚合（均值/P95/成功率/分组快照）统一在收尾时基于已落库用例计算，
 * 保证 DB 中的汇总与用例明细始终一致；单用例的规则判定由评测脚本侧完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalServiceImpl implements EvalService {

    private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_FINISHED = "FINISHED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String CATEGORY_UNKNOWN = "UNKNOWN";

    /**
     * 批次汇总率值的保留小数位
     */
    private static final int RATE_SCALE = 4;

    private final EvalRunMapper evalRunMapper;
    private final EvalCaseResultMapper evalCaseResultMapper;

    @Value("${agent.eval.default-suite:CHAT}")
    private String defaultSuite;

    @Override
    public EvalRun createRun(EvalRunCreateRequest request) {
        EvalRunCreateRequest req = request == null ? new EvalRunCreateRequest() : request;
        EvalRun run = new EvalRun();
        run.setRunId(isBlank(req.getRunId()) ? generateRunId() : req.getRunId().trim());
        run.setSuiteName(isBlank(req.getSuiteName()) ? defaultSuite : req.getSuiteName().trim());
        run.setDatasetVersion(req.getDatasetVersion());
        run.setAgentCommit(req.getAgentCommit());
        run.setModelVersion(req.getModelVersion());
        run.setJudgeVersion(req.getJudgeVersion());
        run.setJudgePromptVersion(req.getJudgePromptVersion());
        run.setStatus(STATUS_RUNNING);
        run.setStartedAt(LocalDateTime.now());
        run.setTotalCases(0);
        run.setSuccessCases(0);
        evalRunMapper.insert(run);
        log.info("Created eval run: runId={}, suite={}", run.getRunId(), run.getSuiteName());
        return run;
    }

    @Override
    @Transactional
    public int saveCaseResults(String runId, List<EvalCaseResultRequest> requests) {
        requireRun(runId);
        if (requests == null || requests.isEmpty()) {
            return 0;
        }
        List<EvalCaseResult> entities = requests.stream()
                .map(request -> toEntity(runId, request))
                .toList();
        int inserted = evalCaseResultMapper.insertBatch(entities);
        log.info("Saved {} eval case results for runId={}", inserted, runId);
        return inserted;
    }

    @Override
    @Transactional
    public EvalRunView finishRun(String runId, String status) {
        requireRun(runId);
        List<EvalCaseResult> cases = evalCaseResultMapper.selectByRunId(runId);

        EvalRun patch = new EvalRun();
        patch.setRunId(runId);
        patch.setStatus(STATUS_FAILED.equalsIgnoreCase(status) ? STATUS_FAILED : STATUS_FINISHED);
        patch.setFinishedAt(LocalDateTime.now());
        patch.setTotalCases(cases.size());
        patch.setSuccessCases(countSuccess(cases));
        patch.setAvgTokens(Percentiles.average(tokens(cases)));
        patch.setP95Tokens(Percentiles.percentile(tokens(cases), 95));
        patch.setAvgDurationMs(Percentiles.average(durations(cases)));
        patch.setP95DurationMs(Percentiles.percentile(durations(cases), 95));
        patch.setAvgQualityScore(Percentiles.average(qualityTotals(cases)));
        patch.setSummaryJson(buildSummary(cases));
        evalRunMapper.updateSummary(patch);

        log.info("Finished eval run: runId={}, status={}, cases={}, success={}",
                runId, patch.getStatus(), patch.getTotalCases(), patch.getSuccessCases());
        return getRun(runId);
    }

    @Override
    public EvalRunView getRun(String runId) {
        EvalRun run = requireRun(runId);
        EvalRunView view = new EvalRunView();
        view.setRun(run);
        view.setCases(evalCaseResultMapper.selectByRunId(runId));
        return view;
    }

    /**
     * 汇总快照：整体成功率、平均调用次数与按分类/难度的分组统计。
     */
    private Map<String, Object> buildSummary(List<EvalCaseResult> cases) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("taskSuccessRate", rate(cases.size(), countSuccess(cases)));
        summary.put("avgLlmCallCount", Percentiles.average(
                cases.stream().map(EvalCaseResult::getLlmCallCount).toList()));
        summary.put("avgToolCallCount", Percentiles.average(
                cases.stream().map(EvalCaseResult::getToolCallCount).toList()));
        summary.put("byCategory", groupStats(cases, EvalCaseResult::getCategory));
        summary.put("byDifficulty", groupStats(cases, EvalCaseResult::getDifficulty));
        return summary;
    }

    /**
     * 按指定字段分组，输出每组的用例数、成功率、平均 Token/耗时/质量分。
     */
    private Map<String, Object> groupStats(List<EvalCaseResult> cases,
                                           Function<EvalCaseResult, String> keyFn) {
        Map<String, List<EvalCaseResult>> grouped = cases.stream()
                .collect(Collectors.groupingBy(caseResult -> {
                    String key = keyFn.apply(caseResult);
                    return isBlank(key) ? CATEGORY_UNKNOWN : key;
                }, LinkedHashMap::new, Collectors.toList()));

        Map<String, Object> result = new LinkedHashMap<>();
        grouped.forEach((key, group) -> {
            int success = countSuccess(group);
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("total", group.size());
            stats.put("success", success);
            stats.put("taskSuccessRate", rate(group.size(), success));
            stats.put("avgTokens", Percentiles.average(tokens(group)));
            stats.put("avgDurationMs", Percentiles.average(durations(group)));
            stats.put("avgQualityScore", Percentiles.average(qualityTotals(group)));
            result.put(key, stats);
        });
        return result;
    }

    private List<Integer> tokens(List<EvalCaseResult> cases) {
        return cases.stream().map(EvalCaseResult::getTotalTokens).toList();
    }

    private List<Integer> durations(List<EvalCaseResult> cases) {
        return cases.stream().map(EvalCaseResult::getDurationMs).toList();
    }

    private List<BigDecimal> qualityTotals(List<EvalCaseResult> cases) {
        return cases.stream().map(EvalCaseResult::getQualityTotal).filter(Objects::nonNull).toList();
    }

    private int countSuccess(List<EvalCaseResult> cases) {
        return (int) cases.stream().filter(c -> Boolean.TRUE.equals(c.getTaskSuccess())).count();
    }

    /**
     * 成功率 = 成功数 / 总数，保留 4 位小数；总数为 0 时返回 null。
     */
    private BigDecimal rate(int total, int success) {
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(success).divide(BigDecimal.valueOf(total), RATE_SCALE, RoundingMode.HALF_UP);
    }

    private EvalCaseResult toEntity(String runId, EvalCaseResultRequest request) {
        if (request == null || isBlank(request.getCaseId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用例 case_id 不能为空");
        }
        EvalCaseResult entity = new EvalCaseResult();
        entity.setRunId(runId);
        entity.setCaseId(request.getCaseId().trim());
        entity.setCategory(request.getCategory());
        entity.setDifficulty(request.getDifficulty());
        entity.setQuery(request.getQuery());
        entity.setTraceId(request.getTraceId());
        entity.setTaskSuccess(request.getTaskSuccess());
        entity.setSuccessReason(request.getSuccessReason());
        entity.setQualityScores(request.getQualityScores());
        entity.setQualityTotal(request.getQualityTotal());
        entity.setToolSelection(request.getToolSelection());
        entity.setParamResult(request.getParamResult());
        entity.setRetrievalResult(request.getRetrievalResult());
        entity.setJudgeReason(request.getJudgeReason());
        entity.setHumanScores(request.getHumanScores());
        entity.setHumanChecked(request.getHumanChecked());
        entity.setTotalInputTokens(request.getTotalInputTokens());
        entity.setTotalOutputTokens(request.getTotalOutputTokens());
        entity.setTotalTokens(request.getTotalTokens());
        entity.setDurationMs(request.getDurationMs());
        entity.setLlmCallCount(request.getLlmCallCount());
        entity.setToolCallCount(request.getToolCallCount());
        entity.setRawOutput(request.getRawOutput());
        entity.setErrorMessage(request.getErrorMessage());
        return entity;
    }

    private EvalRun requireRun(String runId) {
        EvalRun run = isBlank(runId) ? null : evalRunMapper.selectByRunId(runId);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "评测批次不存在: " + runId);
        }
        return run;
    }

    private String generateRunId() {
        return "eval_" + LocalDateTime.now().format(RUN_ID_FORMAT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
