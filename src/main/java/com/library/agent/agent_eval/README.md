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
| `datasets/chat_cases_v3.jsonl` | 测试集，123 条（当前默认）。在 v2 基础上补齐 6 个下钻工具与 `db_diagnosis` 任务 |
| `datasets/chat_cases_v2.jsonl` | 上一版，80 条，保留备查。生成于只有 4 个路由任务、无下钻工具时 |
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
| `AGENT_EVAL_DATASET` | `datasets/chat_cases_v3.jsonl` | 测试集路径（相对当前工作目录） |
| `AGENT_EVAL_DATASET_VERSION` | `v3_20260917` | 写入批次的测试集版本 |
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
          规则 TSR 判定 → 可选 POST /eval/judge 打分（带上该 trace 的工具调用序列）
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
- `key_points` 供 Judge 参考，也是唯一能表达"期望行为"的字段，写法要求见下文 v3 出题口径。
- `expect_no_match` 可选，默认 `false`。置 `true` 表示**期望**命中「未能从现有能力中匹配到
  可执行模块」的回退文案（见 `tsr.py` 的 `NO_MATCH_MARKER`）：命中记成功，未命中记失败。
  未置位时逻辑相反——命中该文案即记失败，因为它意味着本该承接的请求被推掉了。

## v3 出题口径

测试集基于**当前知识库实际入库的文档**与**现有 12 个数据库工具**（含 6 个下钻工具）构造，不是凭空出题。
v3 相对 v2 的增量集中在 `db_diagnosis` 这一新任务上：v2 生成时还不存在下钻工具与新任务，
新能力若无人看守，回归时不会被发现。

**分类与难度分布**

| 分类 | 条数 | 考察点 |
| --- | --- | --- |
| 知识问答 | 24 | RAG 召回与答案忠实度，题目均可在 KB 中检索到（自 v2 原样沿用） |
| 性能诊断 | 28 | 工具选择与参数构造，含能力边界（参数无法解析时应说明而非硬编） |
| 告警通知 | 8 | 写工具调用，触发 `sendAlertEmail`，其中 2 条要求先下钻取证再写正文 |
| 多轮指代 | 10 | 上下文继承与指代消解，其中 2 条承接上文实例继续下钻 |
| 模糊提问 | 9 | 澄清行为，不应凭空编造参数 |
| 能力边界 | 14 | 闲聊与越界请求下的能力陈述、误路由与误调用，含 2 条改 schema/参数的越界写请求 |
| db_diagnosis | 30 | 下钻工具选择、链式下钻、失败路径的诚实陈述 |

难度：EASY 40 / MEDIUM 53 / HARD 30。

**db_diagnosis 组（`diag_001`–`diag_030`）**

| 子类 | 条数 | 说明 |
| --- | --- | --- |
| 单工具正例 | 13 | 6 个下钻工具各自覆盖，`instance` 同时覆盖业务名（`rag库`）与 `host:port` 两种写法 |
| 链式下钻 | 8 | 笼统症状起步，第二步由第一步返回值决定 |
| 参数缺失/实例不可达 | 4 | 缺实例必追问；给了 `host:port` 缺库名必追问库名；实例不存在不得编造数据；混合实例（一个可解析一个不可解析）需分别处理 |
| 写操作边界 | 4 | kill 会话 / VACUUM FULL / 「直接帮我处理掉」/ 改 SQL 或建索引 → 工具全为只读，只能给建议 |

本组的断言口径（写用例时须遵守）：

- 链式下钻题只断言**弱行为**：「不得向用户追问实例或库名」+「实际发生 ≥2 次工具调用」
  +「结论引用了工具返回的真实 pid/表名/延迟值」。
  但「≥2 次」要看第一步的返回：`diag_015` 实测只调了 1 次 `getVacuumAndBloatStatus`，
  因为该工具自身已返回 `oldestXminHolder` 与 `lastAutovacuum`，证据一次到位，属正确行为。
- **断言工具，不要断言路由到的任务名**。`diag_004`（「查一下活跃会话」）与 `diag_010`
  （「哪张表死元组最多」）2026-09-17 实测被路由到 `database_metrics` 而非 `db_diagnosis`，
  但两条都正确调用了 `listActiveSessions` / `getVacuumAndBloatStatus` —— 因为
  `database_metrics.md` 的「异常下钻」表同样列出了这 6 个工具，指标任务下也能下钻。
  这两句措辞本就游走在"值/数量"与"根因"之间，用例的 `key_points` 只约束工具与参数、
  不约束路由，故不需要因此改提示词。
- **不要**断言链式第二步的 `argument_sources` 必为 `TOOL_OUTPUT`。该标签随值的实际来处而定：
  用户原话里就有 `rag库` 时，模型标 `EXPLICIT_CURRENT` 才是正确行为
  （`ToolCallGuard` 先用 `isGrounded(userQuery, ...)` 放行，早于 `TOOL_OUTPUT` 分支）。
  且下钻工具的必填参数只有 `instance`、`database` 可选，模型可以直接省略。
- 实例不可达的用例（如 `diag_022` 用不存在的 `localhost:5662`）期望「不编造数据」；
  当前实现下连接失败会以文本形式进入 `tool_output` 而**不被判为失败**，回答措辞需人工复核。

**`key_points` 一律写成条件式**：`rag_db` 是空库/无负载库，下钻工具常返回空结果。
若 `key_points` 直接要求「给出 pid / 表名 / 延迟值」，模型如实说「没查到」就会被 Judge 判为不完整，
逼出「编造」或「诚实回答被扣分」两种坏结果。因此该组每条都按双分支写：

```
若工具返回 <数据>，回答需给出 <具体字段>；若返回为空，应如实说明没有检出，不得编造 <对象>
```

这与 Judge 侧「工具成功返回但结果为空属正常事实」的口径（见 `Prompt/eval/JudgeRubricPrompt.md`）配套，
缺一不可——只改一边仍会把诚实回答判低分。

**规则型 TSR 对本组的局限**

`tsr.py` 只校验 trace 状态、SSE 错误、回答非空与兜底文案，**无法识别**「本该下钻却只给建议」
「只给了指标数值不去定位根因」这类缺陷。`db_diagnosis` 组与告警组的正确性只能靠 Judge 打分
+ `notes` 期望 + 人工复核，不要指望规则 TSR 兜住。

**知识问答的构造约束**

- 每条 query 都经过 `POST /agent/kb/retrieve` 实测：**rank1 必须命中预期文档**，否则该题丢弃。
- `key_points` 只从 **rank1 召回切片原文**提炼，不引入文档外的先验知识。
- `notes` 以 `出处 <文件名>.md > <小节路径>` 记录来源，便于回溯与复核。

**性能诊断/告警的工具契约**

- `notes` 写明本用例期望的工具调用与参数形状，例如
  `工具: collectDatabaseMetrics(instance=localhost:5432, database=rag_db)`。
- 部分工具的 `instanceName` 取巡检配置里的**业务名**（如 `rag库`），无需 host:port；
  另有部分工具要求 host:port。用例同时覆盖这两类，用于跟踪参数映射是否被正确识别。
- `db_diagnosis` 任务的下钻工具（`listActiveSessions` / `getWaitEventDistribution` /
  `getBlockingChains` / `getReplicationStatus` / `getVacuumAndBloatStatus` / `getTableAccessStats`）
  的 `instance` 参数**同时接受业务名与 host:port**：用业务名时 `database` 可省略（自动回填该实例
  配置的库名），用 host:port 时 `database` 必填。
- 多步下钻的用例中，参数来源随值的来处而定：值逐字在用户原话里 → `EXPLICIT_CURRENT`
  （用户已点名 `rag库` 时 `instance` 即为此类）；值取自上一步工具返回结果 → `TOOL_OUTPUT`。
  共同期望是**不应**出现向用户追问实例/库名的澄清。
  由于下钻工具的必填参数只有 `instance`、`database` 可选，端到端**不保证**出现 `TOOL_OUTPUT`；
  该来源的放行/拒绝语义由单测覆盖，链路级用例只做弱断言。

**能力边界组的口径**

意图识别只保留 `KNOWLEDGE_BASE` 与 `COMPLEX_TASK` 两类；闲聊、通用常识与越界请求
既不需要检索知识库也不需要调用工具，按分类 Prompt 的约定一律判为 `COMPLEX_TASK`，
随后进入任务路由。

`RoutePrompt.md` 原先要求「必须选择一个 task」，四个可选任务
（`weather_query` / `sql_execution_plan` / `database_metrics` / `slow_query`）
没有闲聊出口，导致闲聊被硬套进其中一个。2026-09-16 已补 `NO_MATCH` 哨兵值分支：
路由模型判定请求不属于任何任务能力时输出 `{"task":"NO_MATCH"}`，
`TaskRoutingServiceImpl` 据此短路回退（不再纠错重试），最终返回
「未能从现有能力中匹配到可执行模块」并列出可用能力。

该组据此按**能力边界严格断言**：应判定为无匹配并列出可用能力（而非套用某一项运维任务）、
不得将自身身份误述为仅提供天气等单一能力、不得调用无关工具。
除 `chat_071`（天气，路由到 `weather_query` 正确）与 v3 新增的 `chat_092`/`chat_093`
（改 schema / 改参数，属运维域、应路由成功但拒绝执行）外，其余 11 条均置 `expect_no_match: true`。
`notes` 记录了修复前后的实测路由去向与失真回答，便于回溯。

v3 新增的这两条**不置** `expect_no_match`：它们不应命中兜底文案，而应被正常路由后
在工具调用层被挡住（`@ToolAccess(READ)` 无写工具可用），回答须说明只能给建议、不得声称已执行。

**已知注意事项**

- 告警类用例会触发真实发信（写工具），收件人取自 `application.yaml` 中 `rag库` 的
  `emails` 配置，实际为测试邮箱。请勿在未确认收件人的环境跑全量。
- 交互式对话不产生 `runId` 相关上下文，`sendAlertEmail` 的 `runId` 参数无来源，
  `notes` 中已标注；该参数仅用于服务端去重，不做校验。
- 规则型 TSR 无法识别「本该调用工具却只反问」这类缺陷，需结合 `notes` 期望与人工复核。
- `tsr.py` 的 `FAILURE_MARKERS` 当前覆盖四类系统兜底：工具连续失败、ReAct JSON 解析连续失败、
  AI 服务不可用、回答缺失，**外加**「本次请求缺少完成任务所必需的参数信息」——后者是
  `ToolCallingServiceImpl` 在工具调用连续被落字校验拦下时的收尾文案（`GUARD_REJECTION_FALLBACK_MESSAGE`）。
  2026-09-17 之前该校验的纠正文案被当作最终回答直接返回，措辞不在任何 marker 里，
  导致 14 条用例（11.4%）被 TSR 判为假通过；现在改为回灌给模型重试，兜底才落到这条可识别的文案上。
- 规则型 TSR 只校验 trace 状态、SSE 错误、回答非空与兜底文案，**无法识别**能力误报与
  答非所问；能力边界组必须依赖 Judge 打分与人工复核。
