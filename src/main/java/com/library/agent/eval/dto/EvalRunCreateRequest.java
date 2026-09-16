package com.library.agent.eval.dto;

import lombok.Data;

/**
 * 创建测评批次请求。
 */
@Data
public class EvalRunCreateRequest {

    /**
     * 批次号，如 eval_20260914_1530；缺省由服务端按当前时间生成
     */
    private String runId;

    /**
     * 测评套件：CHAT 整体 / TOOL 工具调用 / RAG 检索；缺省取 agent.eval.default-suite
     */
    private String suiteName;

    /**
     * 测试集版本，如 v1_20260914
     */
    private String datasetVersion;

    /**
     * 被测 Agent 的 git commit
     */
    private String agentCommit;

    /**
     * 被测模型版本标识
     */
    private String modelVersion;

    /**
     * Judge 模型版本标识
     */
    private String judgeVersion;

    /**
     * Judge Prompt 版本
     */
    private String judgePromptVersion;
}
