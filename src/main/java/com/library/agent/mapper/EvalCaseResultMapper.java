package com.library.agent.mapper;

import com.library.agent.entity.EvalCaseResult;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 评测单用例结果 Mapper。
 */
@Mapper
public interface EvalCaseResultMapper {

    /**
     * 新增一条用例结果记录。
     */
    void insert(EvalCaseResult result);

    /**
     * 批量新增用例结果记录。
     */
    int insertBatch(List<EvalCaseResult> results);

    /**
     * 按批次号查询该批次全部用例结果。
     */
    List<EvalCaseResult> selectByRunId(String runId);
}
