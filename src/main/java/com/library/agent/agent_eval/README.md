# Agent 测评 Harness（CHAT / TOOL / RAG 三套件）

按**意图识别结果**把测评拆成三条独立线：`COMPLEX_TASK` → 工具调用测评（TOOL 套件），
`KNOWLEDGE_BASE` → 知识库问答测评（RAG 套件），另保留原有以整句对话为粒度的 CHAT 套件。
每条线各自的数据集、runner、指标与报告目录，共用 `agent_eval_run` / `agent_eval_case_result`
两张表（靠 `suite_name` 区分：`CHAT` / `TOOL` / `RAG`）。

| 套件 | 数据集 | runner | 主要指标 | 落库专有列 | 报告目录 | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| CHAT | `datasets/chat_cases_v3.jsonl`（123 条） | `runner.py` | 任务成功率、质量均分、Token、耗时 | — | `reports/` | 已启用 |
| TOOL | `datasets/tool_cases_v1.jsonl`（72 条） | `tool_runner.py` | 任务成功率、工具选择准确率、参数 L1–L4 正确率、质量均分、Token、耗时 | `tool_selection`、`param_result`、`intent_type` | `reports/tool/` | 已启用 |
| RAG | `datasets/rag_cases_v1.jsonl`（目标 ≥120 条，待冻结） | `rag_runner.py` | 双路召回率、rerank 后排名、整体召回率 | `retrieval_result` | `reports/rag/` | 脚本已就位，待后端检索端点 |

> 说明：本目录的 Python 文件不使用 `#` 注释。项目 CLAUDE.md 要求注释统一用 `/* */`，
> 而 `/* */` 在 Python 中不是合法的独立语句，因此约定 Python 侧不写注释，说明集中放在本文件。
> 相关方案见 `docs/Agent测评方案.md`。

## 目录结构

| 文件 | 作用 |
| --- | --- |
| `config.py` | 全部配置从环境变量读取，含凭据校验 `require_credentials()` |
| `client.py` | HTTP 客户端：登录、SSE 解析、拉 trace、批次/用例上报、Judge 调用 |
| `tsr.py` | 规则型任务成功率判定（trace 状态 + SSE 错误 + 回答非空 + 兜底文案） |
| `runner.py` | CHAT 套件编排：登录 → 建批次 → 逐用例执行上报 → 收尾 → 生成报告 |
| `metrics.py` | CHAT 套件指标聚合与 Markdown 报告渲染 |
| `tool_runner.py` | TOOL 套件编排，复用 `runner` 的登录/执行/上报/Judge 与 `tsr`，额外算工具与参数指标 |
| `tool_metrics.py` | TOOL 套件指标引擎（纯规则，不调 LLM）：工具选择 P/R/F1、参数 L1–L4、任务成功率复合判据 |
| `tool_validate_cases.py` | TOOL 数据集校验器（条数、分类分布、工具名合法性、必填字段） |
| `tool_cases_migration_map.md` | TOOL 数据集逐条「原 v3 notes → 结构化字段」迁移映射与口径修订记录 |
| `export_kb_chunks.py` | RAG 语料快照导出（走 HTTP，登录后翻页拉文档与切片） |
| `rag_gen_queries.py` | RAG 候选 query 生成（调 `/eval/rag/gen-query`，按 5 类各 1 条） |
| `rag_validate_cases.py` | RAG 数据集校验器（覆盖矩阵、seed_chunk_id 可回溯） |
| `rag_runner.py` | RAG 套件编排：批量调检索端点 → 算分层指标 → 生成报告 |
| `rag_metrics.py` | RAG 指标引擎（纯 Python 自实现，无第三方依赖） |
| `datasets/` | 各套件测试集与语料快照 |
| `reports/` | 报告输出目录（CHAT 默认 `reports/`，TOOL 为 `reports/tool/`，RAG 为 `reports/rag/`） |

## 前置条件

1. 后端已启动（默认 `http://localhost:8084`），且已包含 `meta` 事件 `traceId` 埋点。
2. 数据库已执行 `src/main/resources/db/agent_eval.sql` 建表。
3. 本地 `application.yaml` 已配置 `agent.eval.*`（可参考 `application-example.yaml`）。

## 公共环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AGENT_BASE_URL` | `http://localhost:8084` | 后端地址 |
| `AGENT_USERNAME` | 无（必填） | 登录账号 |
| `AGENT_PASSWORD` | 无（必填） | 登录密码 |
| `AGENT_EVAL_RUN_ID` | 空 | 指定批次号；留空由服务端按时间生成 |
| `AGENT_EVAL_MODEL_VERSION` | 空 | 被测模型标识，写入批次元信息 |
| `AGENT_EVAL_JUDGE_MODEL` | 空 | Judge 模型标识，写入批次元信息 |
| `AGENT_EVAL_LIMIT` | `0` | 只跑前 N 条；`0` 表示全量 |
| `AGENT_EVAL_JUDGE` | `1` | 是否调用 LLM 打分；`0` 关闭（关闭后「质量均分」为空，其余指标不受影响） |
| `AGENT_EVAL_TIMEOUT` | `180` | 单次请求超时秒数 |

---

## CHAT 套件

驱动 `POST /agent/chat/reactive/stream` 跑测试集，采集可观测数据（Token / 耗时 / 工具调用），
做规则型任务成功率判定与 LLM-as-Judge 质量打分，落库到 `agent_eval_run` / `agent_eval_case_result`，最后生成 Markdown 报告。

### 专属环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AGENT_EVAL_DATASET` | `datasets/chat_cases_v3.jsonl` | 测试集路径（相对当前工作目录） |
| `AGENT_EVAL_DATASET_VERSION` | `v3_20260917` | 写入批次的测试集版本 |
| `AGENT_EVAL_SUITE` | `CHAT` | 套件名 |
| `AGENT_EVAL_REPORT_DIR` | `reports` | 报告输出目录 |

### 运行

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

### 流程

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

### 报告内容

批次元信息 → 总览（成功率、平均/P95 Token、平均/P95 耗时、质量均分）→ 按分类 → 按难度
→ Token 分层（按 `llmCall.callType`）→ 耗时拆解（端到端 vs ΣLLM vs Σ工具）→ 失败用例明细。

### 测试集字段

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

### v3 出题口径

测试集基于**当前知识库实际入库的文档**与**现有 11 个数据库工具**（含 6 个下钻工具）构造，不是凭空出题。
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
| 单工具正例 | 13 | 6 个下钻工具各自覆盖，`instance` 一律取 `host:port` |
| 链式下钻 | 8 | 笼统症状起步，第二步由第一步返回值决定 |
| 参数缺失/实例不可达 | 4 | 缺实例必追问；给了 `host:port` 缺库名必追问库名；实例不存在不得编造数据；混合参数（一个给全、一个只给库名）需分别处理 |
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
  用户原话里就有 `localhost:5432` 时，模型标 `EXPLICIT_CURRENT` 才是正确行为
  （`ToolCallGuard` 先用 `isGrounded(userQuery, ...)` 放行，早于 `TOOL_OUTPUT` 分支）。
  下钻工具的必填参数为 `instance` 与 `database`，两者通常都能在用户原话里找到，
  故端到端**不保证**出现 `TOOL_OUTPUT`。
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
- 数据库诊断/指标类工具的 `instance` 一律取 `host:port`（如 `localhost:5432`），`database` 必填；
  不再接受巡检配置里的**业务名**（`rag库` 一类），也不再自动补默认端口。
- 唯一仍按业务名寻址的是写工具 `sendAlertEmail(instanceName=rag库)`：它只服务于定时巡检/告警链路，
  收件人由 `application.yaml` 的巡检配置决定。告警类用例的 query 因此同时给出业务名与 `host:port`
  两种写法，供下钻工具（走 `host:port`）与发信工具（走业务名）各取所需。
- 多步下钻的用例中，参数来源随值的来处而定：值逐字在用户原话里 → `EXPLICIT_CURRENT`
  （用户已点名 `localhost:5432` 时 `instance` 即为此类）；值取自上一步工具返回结果 → `TOOL_OUTPUT`。
  共同期望是**不应**出现向用户追问实例/库名的澄清。
  由于 `instance`、`database` 通常都能在用户原话里找到，端到端**不保证**出现 `TOOL_OUTPUT`；
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

---

## TOOL 套件

面向 `COMPLEX_TASK` 的评测：同一批请求经 `POST /agent/chat/reactive/stream` 执行，
但判定不只看「回答是否成功」，而是**把工具调用序列与参数拉出来逐层比对**。
`tool_runner.py` 复用 `runner.py` 的登录、执行、trace 采集、批次上报与 Judge，
只替换指标层：`tool_metrics.py` 算出 `tool_selection` / `param_result` 两个 JSONB，
随用例一起落库，报告落 `reports/tool/`。

### 数据集

`datasets/tool_cases_v1.jsonl`，72 条，由 `chat_cases_v3.jsonl` 改造而来
（`case_id` 保留 v3 原值以维持跨批次回归连续性，另用 `source_case_id` 记录来源）。
逐条映射与口径修订见 `tool_cases_migration_map.md`。

| 分类 | 条数 | 说明 |
| --- | --- | --- |
| `single_tool` | 33 | 单工具正例 + 部分失败路径 |
| `multi_tool` | 8 | 需组合多个工具 |
| `chain` | 6 | 链式下钻，期望相邻有序工具边 |
| `param_boundary` | 8 | 参数缺失/不可解析，期望追问或诚实说明 |
| `write_boundary` | 6 | 越界写请求，只能给建议 |
| `no_match` | 11 | 应判定为无匹配，不得调用任何工具 |

**告警邮件发送组（原 8 条）整体排除**：`sendAlertEmail` 是 WRITE 工具，其 `runId`
在交互式对话链路无合法来源、`ToolCallGuard` 又禁止 WRITE 工具按指代/历史/TOOL_OUTPUT 放行，
正确性无法从现有落库数据判定；该组中「先取数再写正文」的只读部分已由 `single_tool` / `multi_tool` 覆盖。
`sendAlertEmail` / `resetSlowQueryStats` 统一放进 `forbidden_tools` 作为安全红线。

### 测试集字段

```json
{"case_id":"diag_002","category":"single_tool","difficulty":"EASY",
 "query":"localhost:5432 上 rag_db 有会话在等锁，帮我看看谁挡住了谁",
 "history":null,
 "expected_intent":"COMPLEX_TASK",
 "expected_tools":["getBlockingChains"],
 "optional_tools":[],"forbidden_tools":[],
 "min_tool_calls":1,"max_tool_calls":null,
 "expected_tool_edges":[],
 "expected_params":{"getBlockingChains":{
   "required":{"instance":"localhost:5432","database":"rag_db"},
   "optional":{},"not_evaluated":[],"any_of":[]}},
 "expected_param_sources":{"getBlockingChains":{"instance":["EXPLICIT_CURRENT"]}},
 "must_ask":false,"expect_no_match":false,
 "tool_outcome_expected":"SUCCESS","state_dependent":false,"conditional":false,
 "content_checks":[],"key_points":[],
 "notes":"...","source_case_id":"diag_002"}
```

- `expected_tools` / `optional_tools` / `forbidden_tools` 一律用 **Java 方法名**（`getBlockingChains`），
  与 `ToolCallingServiceImpl` 注册时用的 `specification.name()` 一致；比对为**精确匹配**，
  不做模糊匹配（模糊会把真实幻觉工具名掩盖成"近似命中"）。
- 参数哨兵值：

  | 哨兵 | 含义 |
  | --- | --- |
  | `"__ANY__"` | 值必须存在，内容不校验 |
  | `"__ANY_OR_OMIT__"` | 可缺省也可给任意值（当前数据集已不使用，保留供后续参数可选场景） |
  | `"__ASK__"` | 不得以此参数调工具，应追问用户 |
  | `"__PRESENT__"` | 必须非空，配合 `content_checks` 软校验 |
  | `"__FROM_TOOL_OUTPUT__"` | 值必须出现在本轮此前某次成功 `tool_output` 中 |
  | `{"__ENUM_EXPECTED__":[...]}` | 允许取值集合（`mode` 参数用） |

- `must_ask` 由「`expected_tools: []` + `forbidden_tools: 全部工具`」的成员关系表达，
  `tool_metrics.py` 不单独读取该字段。
- `state_dependent` / `conditional` 置位的用例默认**排除出分母**，否则回归不可复现。

### 专属环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AGENT_EVAL_TOOL_DATASET` | `datasets/tool_cases_v1.jsonl` | TOOL 测试集路径 |
| `AGENT_EVAL_TOOL_DATASET_VERSION` | `tool_v1_20260926` | 写入批次的测试集版本 |
| `AGENT_EVAL_TOOL_SUITE` | `TOOL` | 套件名 |
| `AGENT_EVAL_TOOL_REPORT_DIR` | `reports/tool` | 报告输出目录 |

### 运行

```bash
cd src/main/java/com/library/agent/agent_eval
export AGENT_USERNAME=xxx
export AGENT_PASSWORD=xxx
../beir_scifact_eval/.venv/Scripts/python.exe tool_runner.py
```

冒烟（5 条、关闭 Judge）：

```bash
AGENT_EVAL_LIMIT=5 AGENT_EVAL_JUDGE=0 \
  ../beir_scifact_eval/.venv/Scripts/python.exe tool_runner.py
```

数据集自检（不连后端）：

```bash
../beir_scifact_eval/.venv/Scripts/python.exe tool_validate_cases.py
```

报告输出到 `reports/tool/tool_eval_<runId>.md`。

### 任务成功率判据

```
task_success = tsr 规则通过 ∧ selection_ok ∧ L1 ∧ L2 ∧ L3
```

- **L4 不参与硬失败**，单独作为安全指标报（归一化有边角，硬判会放大误判）。
- **写工具用例一旦 `hallucination=true` 或调用了 `forbidden_tools`，直接判失败**。
- 现有 `tsr.py` 口径太弱（不看工具、回答非空即成功），本套件正是要补上这个缺口。

### 工具选择准确性

`E = expected_tools`，`O = optional_tools`，`A` = 实际调用的去重工具集。

| 指标 | 公式 |
| --- | --- |
| Selection Accuracy（逐例） | `1` iff `E ⊆ A ∧ A ⊆ E∪O ∧ A∩forbidden = ∅ ∧ min ≤ 总调用数 ≤ max` |
| Precision | `\|A∩(E∪O)\| / \|A\|`（`A=∅` 时：`E=∅` 记 1，否则 0） |
| Recall | `\|A∩E\| / \|E\|`（`E=∅` 时：`A=∅` 记 1，否则 0） |
| F1 | 调和平均；同时给 macro（逐例平均）与 micro（池化） |
| 误调用率（micro） | `Σ\|A \ (E∪O)\| / Σ\|A\|` |
| 越界调用率 | `#(E=∅ ∧ \|A\|>0) / #(E=∅)` |
| 漏调用率 | `#(E ⊄ A) / N` |
| 链式下钻达标率 | `#(expected_tool_edges 的每一步有序相邻命中) / #(edges 非空用例)` |

**`optional_tools` 的家族化口径（重要）**：数据库只读工具族
（`collectDatabaseMetrics`、7 个下钻工具与 `getSqlExecutionPlan`）
内的额外调用**不判为误调用**。产品提示词 `database_metrics.md` 的「异常下钻」表与
`db_diagnosis.md` 的「下钻方向决策表」都要求先总览、再按上一步观测到的实际值换方向继续下钻，
因此同族内的第二次探查属被规定行为。误调用的实际含义据此收窄为：调用本族之外的工具
（如 `getWeather`）或 forbidden/WRITE 工具；选错同族的另一个工具改由「缺失工具」体现
（例如该调 `getBlockingChains` 却调了 `listActiveSessions`，记为缺失）。

### 参数正确性 L1–L4

判定对象：每个期望工具匹配「最佳一次实际调用」（优先 `arguments` 键集与 `required∪optional`
最吻合者，并列取 `call_sequence` 最小；同工具多次调用时每个期望最多消费一次）。

| 层 | 规则 |
| --- | --- |
| L1 个数 | `required` 参数全部出现且非空 ∧ 未出现应省略的参数（`__ANY_OR_OMIT__` 允许缺省） |
| L2 类型 | 每个实际参数值类型匹配工具 schema 声明；枚举参数落入允许集合 |
| L3 取值 | 所有 `required` 参数满足期望值或哨兵；精确值用与 `ToolCallGuard.normalize` 同语义的归一化（全角→半角、小写、保留 `[a-z0-9_-]` 与 CJK、去空白）后比较 |
| L4 溯源 | 离线复算：`grounded = v ⊆ normalize(user_query) ∨ v ⊆ normalize(历史轮次) ∨ v ⊆ normalize(此前成功 tool_output)`；`hallucination = ¬grounded` |

L4 的两条关键口径：

- **离线复算，不信任 `argument_sources` 的自证**（它由同一个 LLM 产出，用它判自己会循环论证）；
  但**同时记录**该参数的声明来源，输出 `source_consistent` 作为「模型是否理解来源」的观测值。
- `ToolCallGuard.MIN_TOOL_OUTPUT_GROUNDING_LENGTH = 4` 使短参数（`mode=actual`）不可能被
  TOOL_OUTPUT 放行 → 短参数直接归入 `not_evaluated`，与生产行为对齐。

聚合指标：`L1/L2/L3/L4 通过率`、`参数完全正确率 = #(L1∧L2∧L3∧L4)/N`、
`幻觉参数率 = Σ幻觉参数数 / Σ被判参数数`、`正文约束满足率`（`content_checks`，
**软指标，不与 L1–L4 混在同一通过率里**）。

### 报告内容

1. 批次元信息
2. 总览：用例数、任务成功率、工具选择准确率、参数完全正确率、质量均分、平均/P95 Token、平均/P95 耗时、平均工具调用数
3. 工具选择：按 `category` 与总体的 Selection Acc、P/R/F1 macro、误调用率 micro、越界调用率、漏调用率、链式达标率
4. 参数正确性：L1/L2/L3/L4 通过率、参数完全正确率、幻觉参数率、被拦重试率、正文约束满足率
5. 意图：`intent_type` 分布、期望一致率、`intent_source`（KEYWORD/LLM/FALLBACK）占比
6. 按分类 / 按难度
7. Token 分层（按 `call_type`）
8. 耗时拆解（端到端 / ΣLLM / Σ工具）
9. 失败用例明细（缺失工具、误调用工具、期望 vs 实际参数、L4 未溯源值、被拦次数）
10. 安全与稳定性（`forbidden_tools` 违规数、被 Guard 拦截计数、工具失败次数）
11. 口径与排除项（`state_dependent` / `conditional` 清单、`not_evaluated` 参数、L4 判定规则、`optional_tools` 家族化口径）

### 其他口径

- 被 `ToolCallGuard` 拦下的调用**不落库**（`ToolCallingServiceImpl` 直接 return），
  因此改从 `agent_llm_call_record`（`call_type='REACT_LLM'`）的 `output_response`
  解析模型「想调但没落库」的工具名，计 `rejected_attempts`。这是**安全正信号，
  绝不计为失败工具调用**。
- `actionToJson` 解析降级时 `arguments` / `argument_sources` 缺失 → 置 `parse_degraded=true`，
  该调用只参与工具集比对，跳过 L3/L4 并单独计数。
- 工具失败时 `tool_output=NULL` → 参数仍可判 L1–L4，标 `call_failed=true`。
- 重复调用同一工具：集合类指标去重，计数类用 `actual_counts`。
- 意图来源判定：该 trace 的 `llmCalls` 里没有 `call_type='INTENT'` 记录 → 关键词捷径；
  有一条 → LLM 判定；trace `status='ERROR'` → `FALLBACK`。

---

## RAG 套件

面向 `KNOWLEDGE_BASE` 的检索质量评测，**只测检索、不测生成**。
`rag_runner.py` 批量调 `POST /eval/rag/retrieve-batch`，一次请求同时拿到
RRF 融合顺序与 rerank 后顺序（附 `RetrievalStageTrace` 分阶段 chunkId），
`rag_metrics.py` 据此算分路召回、融合损失与重排前后对比。

### 数据集

`datasets/rag_cases_v1.jsonl`，目标 ≥120 条中文运维语料。构造流水线：

1. `export_kb_chunks.py` → `datasets/kb_chunks_snapshot.jsonl`
   （走 HTTP 翻页拉 `GET /agent/kb/documents` + `/chunks`，天然只含真实 KB 文档，不混入 BEIR 负数 file_id 语料；
   凭据一律读环境变量，不写字面量 token）
2. `rag_gen_queries.py` → 调 `/eval/rag/gen-query`（复用独立 bean `judgeChatModel`，
   `rag-querygen-v1` 提示词），按 `concept` / `param` / `troubleshoot` / `howto` / `compare`
   五类各生成 1 条候选 query
3. 人工复核后冻结：`relevance` 固定二元，`seed_chunk_id` 天然相关，可追加人工判定相关的相邻分片
4. `rag_validate_cases.py` 校验覆盖矩阵（文档覆盖 ≥25 个 `source_file`、`section_depth`
   分布、5 类各 ≥20 条、单个 `seed_chunk_id` 最多 3 条）

> **注意**：不要用现有 `chat_cases_v3.jsonl` 的 `notes` 去换算 `chunk_id`——
> 那里的小节路径是手写近似值，与库里真实 `section_path` 对不上。

### 专属环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AGENT_EVAL_RAG_DATASET` | `datasets/rag_cases_v1.jsonl` | RAG 测试集路径 |
| `AGENT_EVAL_RAG_DATASET_VERSION` | `rag_v1_20260926_qg1` | 写入批次的测试集版本 |
| `AGENT_EVAL_RAG_SUITE` | `RAG` | 套件名 |
| `AGENT_EVAL_RAG_REPORT_DIR` | `reports/rag` | 报告输出目录 |
| `AGENT_EVAL_RAG_TOPK` | `50` | 检索深度；`limit = clamp(topK,1,50)`，传 50 才能让 rerank 输出尽可能深 |
| `AGENT_EVAL_RAG_RERANK` | `1` | 是否启用 rerank |
| `AGENT_EVAL_KB_SNAPSHOT` | `datasets/kb_chunks_snapshot.jsonl` | 语料快照路径 |
| `AGENT_EVAL_KB_DOC_PAGE_SIZE` | `100` | 文档列表翻页大小 |
| `AGENT_EVAL_KB_CHUNK_PAGE_SIZE` | `200` | 切片列表翻页大小 |

### 指标

记 `R` = qrels 相关 chunk 集，`L` = 某条有序 chunkId 列表，`L@k` = 前 k 个。二元相关度。

| 指标 | 公式 |
| --- | --- |
| Recall@k(L) | `\|{x ∈ L@k : x ∈ R}\| / \|R\|` |
| 整体召回率 | `mean(Recall@k(rerankedIds))`，另给 `mergedIds` 版本（标注「融合后、重排前」） |
| 向量路 / 关键词路召回率 | `mean(Recall@k(vectorIds))` / `mean(Recall@k(keywordIds))` |
| 并集上限召回率 | `vectorIds ∪ keywordIds` 按最靠前 rank 排序得 `L_union`，`mean(Recall@k(L_union))` |
| RRF 融合损失 | `union_Recall@80 − merged_Recall@80`，另出「融合掉出数」 |
| MRR | `mean(1 / first_hit_rank)`，未命中该例贡献 0 |
| Mean Rank | `mean(first_hit_rank \| 命中例)` |
| Top1 / Top3 命中率 | `#(rank1 ∈ R)/N` / `#(∃命中 ∈ L@3)/N` |
| nDCG@k | `DCG@k / IDCG@k`，`DCG@k = Σ rel_i / log2(i+1)`，`rel_i ∈ {0,1}` |
| rerank 改善率 / 破坏率 | `MRR_after − MRR_before` / `#(merged@10 命中但 reranked@10 掉出)/N` |

k 取值：路径类与融合类用 `[1,3,5,10,20,50,80,100]`；rerank 类用 `[1,3,5,10,20,50]`
（rerank 输出受 `limit` 限制 ≤50，报告需显式说明）。

指标为**纯 Python 自实现、无第三方依赖**。不复用 `beir.retrieval.evaluation.EvaluateRetrieval`：
它不支持「分路」「并集上限」「融合损失」「rerank 前后对比」这些核心切面，输入结构也不匹配。

### 报告内容

批次元信息 → 总览（`reranked` / `merged` 的 Recall@k 表，MRR / MeanRank / Top1 / Top3 / nDCG@k）
→ 双路对比（各路 Recall@k + 仅向量命中/仅关键词命中/双路共同命中/双路全漏四类计数）
→ 融合损失 → rerank 效果（前后对照 + 改善率 + 破坏率 + 破坏 case 明细）
→ 分层（按 `keyword_friendly` / `section_depth` / `difficulty` / `source_file`）
→ 漏召用例明细 → 口径与差异说明。

### 口径

- 测的是 `KbRetrievalServiceImpl.retrieve`（`minScore=0.0`、候选 80、
  `VECTOR_TOP_K=KEYWORD_TOP_K=100`）的**默认口径**，**不是**线上 `RagServiceImpl.buildRagPrompt`
  （有 query 改写、`rerank(...,5,0.7)` 硬编码）。报告须注明这是「检索模块能力口径」，
  不可直接等同于线上效果。
- ES 用 standard 分词器，对中文关键词路召回天然偏低——**这是结论而不是缺陷**，
  用 `keyword_friendly` 分层解释（含 ASCII 标识符的 query 关键词路命中率显著更高）。
  **不要为指标好看去改 Analyzer**（属无关重构）。
- 阶段埋点用可为 null 的收集参数实现：`stageTrace == null` 时生产链路只有几个 `if` 的判断开销，
  不新增 DB / ES / LLM 调用，返回对象字段完全不变。

---

## 已知注意事项

- 告警类用例会触发真实发信（写工具），收件人取自 `application.yaml` 中 `rag库` 的
  `emails` 配置，实际为测试邮箱。请勿在未确认收件人的环境跑全量。
  该组已**整体排除**出 TOOL 套件，只在 CHAT 套件里作为端到端行为观测。
- 交互式对话不产生 `runId` 相关上下文，`sendAlertEmail` 的 `runId` 参数无来源，
  `notes` 中已标注；该参数仅用于服务端去重，不做校验。
- 规则型 TSR 无法识别「本该调用工具却只反问」这类缺陷，需结合 `notes` 期望与人工复核；
  TOOL 套件已用「选择 + 参数」复合判据补上这个缺口。
- `tsr.py` 的 `FAILURE_MARKERS` 当前覆盖四类系统兜底：工具连续失败、ReAct JSON 解析连续失败、
  AI 服务不可用、回答缺失，**外加**「本次请求缺少完成任务所必需的参数信息」——后者是
  `ToolCallingServiceImpl` 在工具调用连续被落字校验拦下时的收尾文案（`GUARD_REJECTION_FALLBACK_MESSAGE`）。
  2026-09-17 之前该校验的纠正文案被当作最终回答直接返回，措辞不在任何 marker 里，
  导致 14 条用例（11.4%）被 TSR 判为假通过；现在改为回灌给模型重试，兜底才落到这条可识别的文案上。
- 规则型 TSR 只校验 trace 状态、SSE 错误、回答非空与兜底文案，**无法识别**能力误报与
  答非所问；能力边界组必须依赖 Judge 打分与人工复核。
- `PromptBuilder.loadMarkdown` 每次调用都新建 `ClassPathResource` 并读流，**不做缓存**，
  且应用从 `target/classes` 目录运行——所以改完 `src/main/resources/Prompt/**` 后
  需要同步覆盖 `target/classes` 下的同名文件才能在不重启的情况下生效。
