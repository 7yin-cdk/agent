package com.library.agent.eval.service;

import com.library.agent.entity.EvalRun;
import com.library.agent.eval.dto.EvalCaseResultRequest;
import com.library.agent.eval.dto.EvalRunCreateRequest;
import com.library.agent.eval.dto.EvalRunView;

import java.util.List;

/**
 * 测评批次与用例结果的持久化、汇总服务。
 */
public interface EvalService {

    /**
     * 创建测评批次，状态置为 RUNNING。
     */
    EvalRun createRun(EvalRunCreateRequest request);

    /**
     * 批量写入某批次的用例结果。
     *
     * @return 实际写入条数
     */
    int saveCaseResults(String runId, List<EvalCaseResultRequest> requests);

    /**
     * 批次收尾：按已落库用例计算汇总指标（均值/P95/成功率/分组快照）并更新批次记录。
     *
     * @param status FINISHED（默认）或 FAILED
     */
    EvalRunView finishRun(String runId, String status);

    /**
     * 查询批次汇总与全部用例结果。
     */
    EvalRunView getRun(String runId);
}
