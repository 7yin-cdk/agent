/* Agent 测评结果表 DDL。
   在 rag_db 数据库中执行一次即可（幂等，可重复执行）。
   用途：沉淀每一轮测评的批次信息与逐用例明细，支撑指标汇总、失败下钻与回归对比。
   依赖：无（纯业务表，不依赖 pgvector）。
   说明：本 DDL 仅新增测评表，不改动任何既有业务表，可安全执行。 */

/* 测评批次表：一次完整跑批一行。suite_name 区分测评套件，用例明细见 agent_eval_case_result。 */
CREATE TABLE IF NOT EXISTS agent_eval_run (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              VARCHAR(64)  NOT NULL,                 -- 批次号，如 eval_20260914_1530，全局唯一
    suite_name          VARCHAR(32)  NOT NULL,                 -- 测评套件：CHAT 整体 / TOOL 工具调用 / RAG 检索
    dataset_version     VARCHAR(64),                           -- 测试集版本，如 v1_20260914
    agent_commit        VARCHAR(64),                           -- 被测 Agent 的 git commit
    model_version       VARCHAR(128),                          -- 被测模型版本标识
    judge_version       VARCHAR(128),                          -- Judge 模型版本标识（整体测评质量打分用）
    judge_prompt_version VARCHAR(64),                          -- Judge Prompt 版本，与 rubric 冻结一致
    status              VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',-- 批次状态：RUNNING 执行中 / FINISHED 已完成 / FAILED 异常中断
    started_at          TIMESTAMPTZ,                           -- 批次开始时间
    finished_at         TIMESTAMPTZ,                           -- 批次结束时间
    total_cases         INTEGER      NOT NULL DEFAULT 0,       -- 用例总数
    success_cases       INTEGER      NOT NULL DEFAULT 0,       -- 判定成功的用例数（任务成功率分子）
    avg_tokens          NUMERIC(12,2),                         -- 平均 Token / 任务
    p95_tokens          NUMERIC(12,2),                         -- Token P95
    avg_duration_ms     NUMERIC(12,2),                         -- 平均端到端耗时（毫秒）
    p95_duration_ms     NUMERIC(12,2),                         -- 耗时 P95（毫秒）
    avg_quality_score   NUMERIC(6,3),                          -- 回答质量加权均分（1~5）
    summary_json        JSONB        NOT NULL DEFAULT '{}'::jsonb, -- 全量指标快照（分层/分组聚合结果）
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

/* 批次号唯一，防止重复落库；按套件 + 时间倒序取最近批次做回归对比 */
CREATE UNIQUE INDEX IF NOT EXISTS uk_eval_run_run_id
    ON agent_eval_run (run_id);
CREATE INDEX IF NOT EXISTS idx_eval_run_suite_started
    ON agent_eval_run (suite_name, started_at DESC);

/* 单用例结果表：一行一用例，承载三大测评模块的判定明细。 */
CREATE TABLE IF NOT EXISTS agent_eval_case_result (
    id                 BIGSERIAL PRIMARY KEY,
    run_id             VARCHAR(64)  NOT NULL,                  -- 关联 agent_eval_run.run_id
    case_id            VARCHAR(64)  NOT NULL,                  -- 用例编号，如 chat_001 / tool_042 / rag_zh_003
    category           VARCHAR(32),                            -- 用例分类：知识问答/诊断/告警/越界、single_tool/multi_tool/hard_negative 等
    difficulty         VARCHAR(16),                            -- 难度：EASY / MEDIUM / HARD
    query              TEXT,                                   -- 用户输入原文（便于失败回溯）
    trace_id           VARCHAR(64),                            -- 关联可观测三表 agent_conversation_trace / _llm_call_record / _tool_call_record
    task_success       BOOLEAN,                                -- 任务是否成功（整体测评 TSR 分子）
    success_reason     TEXT,                                   -- 成功/失败判定理由（规则命中项或 Judge 结论）
    quality_scores     JSONB,                                  -- 回答质量六维原始分 {"correctness":5,"completeness":4,...}
    quality_total      NUMERIC(6,3),                           -- 质量加权总分（1~5），便于聚合排序
    tool_selection     JSONB,                                  -- 工具选择明细 {"expected":[],"actual":[],"precision":..,"recall":..,"f1":..,"overcall":..}
    param_result       JSONB,                                  -- 参数校验明细 {"count_ok":..,"type_ok":..,"value_ok":..,"grounded":..,"hallucination":..}
    retrieval_result   JSONB,                                  -- 检索阶段明细 {"vector_hit":..,"keyword_hit":..,"rrf_rank":..,"rerank_rank":..}
    judge_reason       TEXT,                                   -- Judge 判定理由原文
    human_scores       JSONB,                                  -- 人工抽检评分（校准 Judge 一致性用）
    human_checked      BOOLEAN      NOT NULL DEFAULT FALSE,    -- 是否已人工复核
    total_input_tokens INTEGER,                                -- 输入 Token 合计（取自 trace）
    total_output_tokens INTEGER,                               -- 输出 Token 合计（取自 trace）
    total_tokens       INTEGER,                                -- 总 Token（输入 + 输出）
    duration_ms        INTEGER,                                -- 端到端耗时（毫秒）
    llm_call_count     INTEGER,                                -- LLM 调用次数（衡量路由/ReAct 开销）
    tool_call_count    INTEGER,                                -- 工具调用次数
    raw_output         TEXT,                                   -- Agent 最终输出原文（质量打分依据）
    error_message      TEXT,                                   -- 异常信息（trace.status 非成功时）
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

/* 同一批次内用例不重复；支撑按批次拉取明细 */
CREATE UNIQUE INDEX IF NOT EXISTS uk_eval_case_run_case
    ON agent_eval_case_result (run_id, case_id);
/* 失败下钻：按批次筛失败用例 */
CREATE INDEX IF NOT EXISTS idx_eval_case_run_success
    ON agent_eval_case_result (run_id, task_success);
/* 分组统计：按分类/难度切片 */
CREATE INDEX IF NOT EXISTS idx_eval_case_run_category
    ON agent_eval_case_result (run_id, category, difficulty);
/* 按用例跨批次回归对比 */
CREATE INDEX IF NOT EXISTS idx_eval_case_case_id
    ON agent_eval_case_result (case_id, created_at DESC);
/* 关联可观测 trace 明细 */
CREATE INDEX IF NOT EXISTS idx_eval_case_trace
    ON agent_eval_case_result (trace_id);

COMMENT ON COLUMN agent_eval_run.suite_name IS '测评套件：CHAT 整体测评 / TOOL 工具调用测评 / RAG 检索测评';
COMMENT ON COLUMN agent_eval_run.status IS '批次状态：RUNNING 执行中 / FINISHED 已完成 / FAILED 异常中断';
COMMENT ON COLUMN agent_eval_case_result.difficulty IS '用例难度：EASY / MEDIUM / HARD';
COMMENT ON COLUMN agent_eval_case_result.task_success IS '任务是否成功，整体测评任务成功率(Task Success Rate)的分子';
COMMENT ON COLUMN agent_eval_case_result.quality_total IS '回答质量六维加权总分，权重：正确性30%/完整性20%/可操作性20%/相关性15%/安全10%/格式5%';
