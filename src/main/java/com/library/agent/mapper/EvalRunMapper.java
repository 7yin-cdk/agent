package com.library.agent.mapper;

import com.library.agent.entity.EvalRun;
import org.apache.ibatis.annotations.Mapper;

/**
 * 测评批次 Mapper。
 */
@Mapper
public interface EvalRunMapper {

    /**
     * 新增一条测评批次记录。
     */
    void insert(EvalRun run);

    /**
     * 更新批次的汇总指标与状态（按 run_id 定位），用于批次收尾。
     */
    int updateSummary(EvalRun run);

    /**
     * 按批次号查询批次记录。
     */
    EvalRun selectByRunId(String runId);
}
