package com.library.agent.eval.controller;

import com.library.agent.auth.context.UserContextHolder;
import com.library.agent.entity.EvalRun;
import com.library.agent.eval.dto.EvalCaseResultRequest;
import com.library.agent.eval.dto.EvalFinishRequest;
import com.library.agent.eval.dto.EvalJudgeRequest;
import com.library.agent.eval.dto.EvalJudgeResponse;
import com.library.agent.eval.dto.EvalRunCreateRequest;
import com.library.agent.eval.dto.EvalRunView;
import com.library.agent.eval.service.EvalJudgeService;
import com.library.agent.eval.service.EvalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 测评接口控制器。
 * <p>
 * 测评数据不区分用户（结果表无 user_id 列），是面向内部的质量工具，但接口仍需登录鉴权。
 * 用例级 Token 与耗时由脚本从 {@code GET /agent/observability/traces/{traceId}} 采集后
 * 随用例结果一并上报，本控制器只负责落库与批次汇总。
 */
@RestController
@RequestMapping("/eval")
@RequiredArgsConstructor
public class EvalController {

    private final EvalService evalService;
    private final EvalJudgeService evalJudgeService;

    /**
     * 创建测评批次。
     */
    @PostMapping("/runs")
    public EvalRun createRun(@RequestBody(required = false) EvalRunCreateRequest request) {
        requireUserId();
        return evalService.createRun(request);
    }

    /**
     * 批量写入某批次的用例结果。
     */
    @PostMapping("/runs/{runId}/cases")
    public Map<String, Object> saveCases(@PathVariable String runId,
                                         @RequestBody List<EvalCaseResultRequest> requests) {
        requireUserId();
        int inserted = evalService.saveCaseResults(runId, requests);
        return Map.of("inserted", inserted);
    }

    /**
     * 批次收尾：计算并落库汇总指标。
     */
    @PostMapping("/runs/{runId}/finish")
    public EvalRunView finish(@PathVariable String runId,
                              @RequestBody(required = false) EvalFinishRequest request) {
        requireUserId();
        String status = request == null ? null : request.getStatus();
        return evalService.finishRun(runId, status);
    }

    /**
     * 查询批次汇总与全部用例结果。
     */
    @GetMapping("/runs/{runId}")
    public EvalRunView getRun(@PathVariable String runId) {
        requireUserId();
        return evalService.getRun(runId);
    }

    /**
     * 单条回答质量打分。
     */
    @PostMapping("/judge")
    public EvalJudgeResponse judge(@RequestBody EvalJudgeRequest request) {
        requireUserId();
        return evalJudgeService.judge(request);
    }

    /**
     * 查询当前生效的 Judge rubric 版本；评测脚本据此写入批次元信息，避免版本号在两处硬编码。
     */
    @GetMapping("/judge/version")
    public Map<String, Object> judgeVersion() {
        requireUserId();
        return Map.of("rubricVersion", EvalJudgeService.RUBRIC_VERSION);
    }

    private Long requireUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return userId;
    }
}
