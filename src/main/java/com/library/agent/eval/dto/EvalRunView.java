package com.library.agent.eval.dto;

import com.library.agent.entity.EvalCaseResult;
import com.library.agent.entity.EvalRun;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 测评批次视图：批次汇总 + 逐用例结果，供报告渲染与失败下钻。
 */
@Data
public class EvalRunView {

    /**
     * 批次记录（含汇总指标）
     */
    private EvalRun run;

    /**
     * 该批次全部用例结果
     */
    private List<EvalCaseResult> cases = new ArrayList<>();
}
