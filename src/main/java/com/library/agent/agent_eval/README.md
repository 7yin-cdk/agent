# Agent 整体测评 Harness（CHAT 套件）

驱动 `POST /agent/chat/reactive/stream` 跑测试集，采集可观测数据（Token / 耗时 / 工具调用），
做规则型任务成功率判定与 LLM-as-Judge 质量打分，落库到 `agent_eval_run` / `agent_eval_case_result`，最后生成 Markdown 报告。

> 说明：本目录的 Python 文件不使用 `#` 注释。项目 CLAUDE.md 要求注释统一用 `/* */`，
> 而 `/* */` 在 Python 中不是合法的独立语句，因此约定 Python 侧不写注释，说明集中放在本文件。
> 相关方案见 `docs/Agent测评方案.md`。

## 目录结构

| 文件 | 作用 |
| --- | --- |
| `config.py` | 全部配置从环境变量读取，含凭据校验 `require_credentials()` |
| `client.py` | HTTP 客户端：登录、SSE 解析、拉 trace、批次/用例上报、Judge 调用 |
| `tsr.py` | 规则型任务成功率判定（trace 状态 + SSE 错误 + 回答非空 + 兜底文案） |
| `runner.py` | 主编排：登录 → 建批次 → 逐用例执行上报 → 收尾 → 生成报告 |
| `metrics.py` | 批次指标聚合与 Markdown 报告渲染 |
| `datasets/chat_cases_v2.jsonl` | 测试集，80 条（当前默认，基于已入库 PG18 运维文档与现有工具构造） |
| `datasets/chat_cases_v1.jsonl` | 早期版本，保留备查；部分题目超出知识库覆盖范围 |
| `reports/` | 报告输出目录（默认） |

## 前置条件

1. 后端已启动（默认 `http://localhost:8084`），且已包含 `meta` 事件 `traceId` 埋点。
2. 数据库已执行 `src/main/resources/db/agent_eval.sql` 建表。
3. 本地 `application.yaml` 已配置 `agent.eval.*`（可参考 `application-example.yaml`）。

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AGENT_BASE_URL` | `http://localhost:8084` | 后端地址 |
| `AGENT_USERNAME` | 无（必填） | 登录账号 |
| `AGENT_PASSWORD` | 无（必填） | 登录密码 |
| `AGENT_EVAL_DATASET` | `datasets/chat_cases_v2.jsonl` | 测试集路径（相对当前工作目录） |
| `AGENT_EVAL_DATASET_VERSION` | `v2_20260916` | 写入批次的测试集版本 |
| `AGENT_EVAL_SUITE` | `CHAT` | 套件名 |
| `AGENT_EVAL_RUN_ID` | 空 | 指定批次号；留空由服务端按时间生成 |
| `AGENT_EVAL_MODEL_VERSION` | 空 | 被测模型标识，写入批次元信息 |
| `AGENT_EVAL_JUDGE_MODEL` | 空 | Judge 模型标识，写入批次元信息 |
| `AGENT_EVAL_LIMIT` | `0` | 只跑前 N 条；`0` 表示全量 |
| `AGENT_EVAL_JUDGE` | `1` | 是否调用 LLM 打分；`0` 关闭 |
| `AGENT_EVAL_TIMEOUT` | `180` | 单次请求超时秒数 |
| `AGENT_EVAL_REPORT_DIR` | `reports` | 报告输出目录 |

## 运行

复用已有虚拟环境：

```bash
cd src/main/java/com/library/agent/agent_eval
export AGENT_USERNAME=xxx
export AGENT_PASSWORD=xxx
../beir_scifact_eval/.venv/Scripts/python.exe runner.py
```

冒烟（只跑前 5 条、关闭 Judge）：

```bash
AGENT_EVAL_LIMIT=5 AGENT_EVAL_JUDGE=0 \
  ../beir_scifact_eval/.venv/Scripts/python.exe runner.py
```

报告输出到 `reports/eval_<runId>.md`。

## 流程

```
login → GET /eval/judge/version → POST /eval/runs
      → 逐用例：
          history 存在时先建会话并重放历史轮次
          POST /agent/chat/reactive/stream（末轮）→ 从 meta 取 traceId
          GET /agent/observability/traces/{traceId} → Token/耗时/调用明细
          规则 TSR 判定 → 可选 POST /eval/judge 打分
      → 每 10 条 POST /eval/runs/{runId}/cases 落库
      → POST /eval/runs/{runId}/finish（Java 侧算聚合）
      → metrics.py 渲染报告
```

## 报告内容

批次元信息 → 总览（成功率、平均/P95 Token、平均/P95 耗时、质量均分）→ 按分类 → 按难度
→ Token 分层（按 `llmCall.callType`）→ 耗时拆解（端到端 vs ΣLLM vs Σ工具）→ 失败用例明细。

## 测试集字段

每行一个 JSON 对象：

```json
{"case_id":"chat_001","category":"知识问答","difficulty":"EASY",
 "query":"ANALYZE 收集什么统计信息？","key_points":["..."],
 "notes":"出处 sql-analyze.md > ANALYZE > Description/Notes","history":["上一轮用户输入"]}
```

- `history` 可选，仅多轮指代用例使用；存在时先建会话并重放历史轮次再接末轮。
- `key_points` 供 Judge 参考；为空时 Judge 走 reference-free 打分。
- `notes` 是评测口径说明（人类可读，不参与打分）。

## v2 出题口径

测试集基于**当前知识库实际入库的文档**与**现有 6 个工具**构造，不是凭空出题。

**分类与难度分布**

| 分类 | 条数 | 考察点 |
| --- | --- | --- |
| 知识问答 | 24 | RAG 召回与答案忠实度，题目均可在 KB 中检索到 |
| 性能诊断 | 22 | 工具选择与参数构造，含能力边界（参数无法解析时应说明而非硬编） |
| 告警通知 | 6 | 写工具调用，触发 `sendAlertEmail` |
| 多轮指代 | 8 | 上下文继承与指代消解 |
| 模糊提问 | 8 | 澄清行为，不应凭空编造参数 |
| 能力边界 | 12 | 闲聊与越界请求下的能力陈述、误路由与误调用 |

难度：EASY 27 / MEDIUM 34 / HARD 19。

**知识问答的构造约束**

- 每条 query 都经过 `POST /agent/kb/retrieve` 实测：**rank1 必须命中预期文档**，否则该题丢弃。
- `key_points` 只从 **rank1 召回切片原文**提炼，不引入文档外的先验知识。
- `notes` 以 `出处 <文件名>.md > <小节路径>` 记录来源，便于回溯与复核。

**性能诊断/告警的工具契约**

- `notes` 写明本用例期望的工具调用与参数形状，例如
  `工具: collectDatabaseMetrics(instance=localhost:5432, database=rag_db)`。
- 部分工具的 `instanceName` 取巡检配置里的**业务名**（如 `rag库`），无需 host:port；
  另有部分工具要求 host:port。用例同时覆盖这两类，用于跟踪参数映射是否被正确识别。

**能力边界组的口径**

意图识别只保留 `KNOWLEDGE_BASE` 与 `COMPLEX_TASK` 两类；闲聊、通用常识与越界请求
既不需要检索知识库也不需要调用工具，按分类 Prompt 的约定一律判为 `COMPLEX_TASK`，
随后进入任务路由。而 `RoutePrompt.md` 要求「必须选择一个 task」，四个可选任务
（`weather_query` / `sql_execution_plan` / `database_metrics` / `slow_query`）
没有闲聊出口，因此闲聊会被硬套进其中一个任务。

该组用例据此按**能力边界严格断言**：不得将自身身份误述为仅提供天气等单一能力、
不得把无关请求套成运维任务并索要实例参数、不得调用无关工具。`notes` 记录了 2026-09-16
实测的路由去向与失真回答，便于回溯。组内 `chat_071`（天气）是唯一路由正确的对照用例。

**已知注意事项**

- 告警类用例会触发真实发信（写工具），收件人取自 `application.yaml` 中 `rag库` 的
  `emails` 配置，实际为测试邮箱。请勿在未确认收件人的环境跑全量。
- 交互式对话不产生 `runId` 相关上下文，`sendAlertEmail` 的 `runId` 参数无来源，
  `notes` 中已标注；该参数仅用于服务端去重，不做校验。
- 规则型 TSR 无法识别「本该调用工具却只反问」这类缺陷，需结合 `notes` 期望与人工复核。
- 规则型 TSR 只校验 trace 状态、SSE 错误、回答非空与兜底文案，**无法识别**能力误报与
  答非所问；能力边界组必须依赖 Judge 打分与人工复核。
