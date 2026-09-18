# 智能数据库运维 Agent —— 测评方案

> 版本：v1.0　创建日期：2026-09-14
> 适用对象：`com.library.agent`（LangChain4j + Spring Boot 3 数据库运维 Agent）

---

## 一、测评目标与范围

本方案面向当前 Agent 项目，围绕**回答质量、执行效率、工具调用准确性、RAG 检索质量**四个维度建立可量化、可复现、可回归的测评体系，回答三个问题：

1. **Agent 整体好不好用？** —— 任务完成后，成功率、成本、耗时、回答质量是否达标。
2. **Agent 会不会正确用工具？** —— 复杂任务下，是否调用了正确的工具、参数是否正确且可溯源。
3. **Agent 检索得准不准？** —— 双路召回各环节与重排序，能否把相关文档召回并排在前面。

测评范围**只覆盖线上真实链路**（`/agent/chat/reactive/stream` → 意图路由 → RAG 检索 → ReAct 工具编排 → 输出），不做单元级 mock 测评，以保证结论可信。

### 测评内容与方案对应关系

| 用户要求 | 本方案章节 | 核心指标 |
| --- | --- | --- |
| (1) 整体测评 | [第四章 4.1](#41-整体测评) | 任务成功率、Token 成本、任务耗时、回答质量得分 |
| (2) 复杂任务工具调用测评 | [第四章 4.2](#42-复杂任务工具调用测评) | 工具调用成功率、参数正确率（个数/类型/来源）、成本消耗 |
| (3) RAG 测评 | [第四章 4.3](#43-rag-测评) | 整体召回率、双路召回阶段召回率、重排序排名 |

---

## 二、测评总体架构

```
┌──────────────────────────────────────────────────────────────────┐
│  测试集层  eval/datasets/*.jsonl                                  │
│  ├─ chat_cases.jsonl        整体测评用例（问题 + 参考答案要点）      │
│  ├─ tool_cases.jsonl        工具调用用例（问题 + 期望工具/参数）     │
│  └─ rag_cases.jsonl         RAG 用例（query + 相关 chunk 标注）     │
└───────────────────────────┬──────────────────────────────────────┘
                            │ 逐条驱动
┌───────────────────────────▼──────────────────────────────────────┐
│  执行层  Eval Runner（Python 或 Java）                             │
│  ├─ 调用真实接口 POST /agent/chat/reactive/stream                  │
│  ├─ 采集 SSE 事件流（meta/status/delta/done）组装最终答案          │
│  └─ 通过 trace_id 关联可观测三表，取 Token / 耗时 / 工具调用明细    │
└───────────────────────────┬──────────────────────────────────────┘
                            │ 原始结果
┌───────────────────────────▼──────────────────────────────────────┐
│  判定层                                                            │
│  ├─ 规则判定：工具名/参数精确比对、trace.status、召回命中          │
│  ├─ LLM-as-Judge：回答质量多维打分（固定 rubric + 固定模型）        │
│  └─ 人工抽检：10%~20% 复核，校准 Judge 一致性                      │
└───────────────────────────┬──────────────────────────────────────┘
                            │ 指标
┌───────────────────────────▼──────────────────────────────────────┐
│  存储与报告层                                                      │
│  ├─ 落库 agent_eval_run / agent_eval_case_result（可回归对比）      │
│  └─ 生成 Markdown / HTML 报告（总体指标 + 分组维度 + 失败明细）      │
└──────────────────────────────────────────────────────────────────┘
```

### 已具备的能力（直接复用）

- **可观测三表已完整持久化 Token 与耗时**，无需额外埋点：
  - `agent_conversation_trace`：`total_tokens` / `total_input_tokens` / `total_output_tokens` / `total_duration_ms` / `llm_call_count` / `tool_call_count` / `status` / `error_message` / `intent_type`。
  - `agent_llm_call_record`：`call_type` / `model_name` / `input_tokens` / `output_tokens` / `duration_ms`，可做**分层成本归因**。
  - `agent_tool_call_record`：`tool_name` / `tool_input` / `tool_output` / `success` / `duration_ms`，工具测评的核心数据源。
  - 查询入口：`GET /agent/observability/traces/{traceId}`。
- **BEIR SciFact 检索评测链路已存在**：`/eval/beir/scifact/*`（import / search-chunks / search-docs），语料与 qrels 位于 `beir_scifact_eval/`，结果命中由 Java 返回、指标由 Python 计算。

### 需要新增的改造（本方案前置工作）

| 编号 | 改造项 | 说明 | 优先级 |
| --- | --- | --- | --- |
| A1 | 暴露检索各阶段中间结果 | `KbRetrievalServiceImpl.retrieve` 目前只返回最终 `RetrievedChunk`，向量路/ES 路/RRF/重排前后均为局部变量。需新增 `RagStageTrace`（各阶段 chunkId 列表 + 分数 + 排名）并随 trace 落库或在 eval 接口返回 | P0（RAG 分阶段测评必需） |
| A2 | 工具调用记录补充"入参原文" | `tool_input` 已是 JSON 字符串，可直接用于参数比对，确认序列化字段名与 `@P` 参数名一致即可 | P1（校验即可） |
| A3 | 新增测评结果表 | `agent_eval_run` + `agent_eval_case_result`，用于结果沉淀与回归对比 | P1 |
| A4 | SSE 首字节时延（TTFT）埋点 | 如关注流式体验，记录 `status` 首事件时间戳 | P2（可选） |

---

## 三、测试集设计

### 3.1 通用原则

- 格式统一为 **JSONL**（每行一条用例），版本化命名（`v1_20260914`）。
- 每条用例含：`case_id`、`category`、`query`、`expected`（结构化期望）、`difficulty`、`notes`。
- **分层抽样**：简单 / 中等 / 复杂 ≒ 4:4:2，覆盖单轮、多轮、含上下文指代、异常边界。
- 数据来源优先级：**真实历史 trace 挖掘**（`agent_conversation_trace.user_query`）> 运维文档衍生 > 人工构造。

### 3.2 测试集规模建议

| 测试集 | 建议规模 | 说明 |
| --- | --- | --- |
| 整体测评 `chat_cases` | ≥ 80 条 | 含知识问答、诊断、告警、纯闲聊/越界；当前 `chat_cases_v3.jsonl` 为 123 条 |
| 工具调用 `tool_cases` | ≥ 100 条 | 12 个数据库工具 × 正例/硬负例/多工具/参数缺失 |
| RAG `rag_cases` | ≥ 100 条 | BEIR SciFact 全量 + 自建中文运维集 ≥ 50 条 |

### 3.3 工具调用用例模板（示例）

```json
{"case_id":"tool_001","category":"single_tool","query":"帮我采集一下 192.168.1.100:5432 上 rag_db 的性能指标","expected_tools":["采集PostgreSQL数据库实例的八项核心性能指标：活跃会话数、缓冲池命中率、锁等待会话数、每秒事务数、主从复制延迟、死元组比例、后端写入缓冲区占比、事务空闲未关闭连接数"],"expected_params":{"collectDatabaseMetrics":{"instance":"192.168.1.100:5432","database":"rag_db"}},"difficulty":"easy"}
{"case_id":"tool_042","category":"multi_tool","query":"诊断下 rag 库，看健康指标和慢查询，有问题就发告警","expected_tools":["获取指定数据库实例的实时健康指标，返回各指标原始数值，供判断数据库是否异常。注意：该工具只返回数据，不包含异常判定结论","查询数据库中平均执行时间最长的Top 10慢查询，基于pg_stat_statements扩展，返回查询文本、执行次数、各维度耗时及缓冲区命中率等关键信息"],"optional_tools":["向指定数据库实例的预配置告警联系人发送告警邮件，收件人由配置决定。邮件正文需包含异常指标、当前值、阈值与优化建议"],"difficulty":"hard","order_sensitive":false}
{"case_id":"tool_071","category":"hard_negative","query":"慢查询一般是怎么产生的？给我讲讲原理","expected_tools":[],"difficulty":"medium","notes":"纯知识问答，不应调用任何工具"}
{"case_id":"tool_088","category":"param_missing","query":"帮我查一下慢查询","expected_params":{"getTopSlowQueries":{"instance":"__ASK_USER__"}},"difficulty":"hard","notes":"instance 缺失，应追问而非编造"}
```

> 工具 `name` 当前为 `@Tool` 注解的整句描述（LangChain4j 约定），比对时建议用**整句精确匹配**，同时在 harness 中维护「整句 ↔ 短名」映射表便于报告阅读。

**`db_diagnosis` 诊断组用例要点**

已在 `chat_cases_v3.jsonl` 落地 30 条（`diag_001`–`diag_030`）：
单工具正例 13 条（6 个工具各自覆盖，`instance` 覆盖业务名与 `host:port` 两种写法）、
链式下钻 8 条、参数缺失/实例不可达 4 条、写操作边界 4 条。

除单工具正例外，该组需要专门覆盖**链式下钻**（ReAct 多步：先用总览类工具定位方向，再用专项工具取证据）：

```json
{"case_id":"tool_101","category":"chained_drilldown","query":"rag库 现在很卡，帮我看看是谁在锁表","expected_tools":["获取指定数据库当前的锁阻塞关系（阻塞方 pid/用户/应用/状态/事务已开启时长/backend_xmin/正在执行的 SQL、持有锁的模式与对象、是否链源头，以及等待方 pid/用户/状态/已等待时长/正在执行的 SQL、被该阻塞方挡住的会话数），用于定位锁等待的根因会话与长事务"],"expected_param_sources":{"getBlockingChains":{"instance":"EXPLICIT_CURRENT"}},"difficulty":"hard","notes":"业务名场景；instance 逐字出现在用户原话中"}
{"case_id":"tool_102","category":"hard_negative","query":"你好，今天天气怎么样","expected_tools":[],"difficulty":"easy","notes":"不应误触发任何下钻工具"}
```

断言要点的口径（**不要断言来源必须是 `TOOL_OUTPUT`**）：

- 后续步骤的 `argument_sources` 取值随参数值的来处而定：值逐字出现在用户原话里 → `EXPLICIT_CURRENT`
  （`tool_101` 的 `instance=rag库` 即属此类，实测模型也是这么标的）；值来自上一步工具返回结果 → `TOOL_OUTPUT`。
- 共同断言是**不得出现向用户追问实例/库名的澄清**，以及后续步骤确实调用了下钻工具。
- 当前 6 个下钻工具的必填参数只有 `instance`，`database` 为可选（业务名场景自动回填）。
  因此"值不在原话里"的真实窗口只有两种：模型在后续步骤显式带上取自上一步输出的 `database`，
  或模型改用上一步回显的 `host:port` 形式调用。两者都由模型自主选择，端到端**不保证**出现
  `TOOL_OUTPUT`——`TOOL_OUTPUT` 的放行/拒绝语义由 `ToolCallGuardTest` 与
  `ToolCallingServiceGroundingTest` 的确定性单测覆盖，链路级用例只做"不追问 + 真调用"的弱断言。

**`key_points` 必须写成条件式（被测库是安静库）**

`rag_db` 是空库/无负载库，下钻工具常返回空结果。若 `key_points` 直接要求"给出 pid / 表名 / 延迟值"，
模型如实说"没查到"就会被 Judge 判为不完整，逼出两种坏结果：模型编造数据，或诚实回答被扣分。
因此该组每条都按「有数据 → 给出 X；无数据 → 如实说明没有，不得编造」的双分支写：

```json
{"key_points":["应调用锁阻塞下钻工具",
  "若工具返回阻塞链，回答需给出阻塞方与等待方的 pid 及持锁对象；若返回为空，应如实说明当前没有检出锁阻塞，不得编造 pid 或持锁对象"]}
```

配合 Judge 侧「工具成功返回但结果为空属正常事实」的口径（见 4.1.4），"如实说没有"才不会被冤枉。

### 3.4 RAG 用例模板

```json
{"case_id":"rag_zh_003","query":"pg_stat_statements 统计信息怎么重置","relevant_chunk_ids":[10231,10232],"source":"manual","difficulty":"easy"}
```

- 标注方式：从知识库 `text_chunk` 中人工/半自动（LLM 生成 query 后人工确认）标注相关 chunk，形成 qrels。
- 英文链路正确性复用 `beir_scifact_eval/exported/scifact/chunk_test_set.jsonl`。

---

## 四、指标定义

### 4.1 整体测评

#### 4.1.1 任务成功率（Task Success Rate, TSR）

**定义**：成功完成预期任务的用例占比。

```
TSR = 成功用例数 / 总用例数
```

**成功判定（按用例类型选用，多条件取交集）**：

| 判定方式 | 适用场景 | 判定规则 |
| --- | --- | --- |
| 结构化规则判定 | 工具类任务 | `trace.status = SUCCESS` 且 工具 `success = true` 且 关键输出字段存在 |
| LLM-as-Judge 判定 | 开放问答 | Judge 输出 `PASS / FAIL`（附判定理由） |
| 兜底话术检测 | 全部 | 命中"抱歉/无法/暂不支持/系统异常"等模板 ⇒ FAIL，反向识别假成功 |

> `trace.status` 与 `error_message` 直接从 `/agent/observability/traces/{traceId}` 读取。

**分组维度**：按 `category`（知识问答/诊断/告警/越界）、`difficulty`、`intent_type` 分别统计，暴露结构性短板。

#### 4.1.2 任务成本（Token 消耗）

**主指标**：平均 Token / 任务。

```
AvgTokens = Σ trace.total_tokens / N
```

同时报告 **P50 / P95 / Max**，以及**分层归因**（来自 `agent_llm_call_record.call_type`）：

| 环节 | 数据来源 `call_type` | 说明 |
| --- | --- | --- |
| 意图识别 | 路由调用 | 每轮固定开销，用于评估路由是否过重 |
| 查询改写 | 改写调用 | 影响 RAG 质量与成本 |
| 工具调用（ReAct） | 工具规划/结果解读 | ReAct 循环次数直接放大成本 |
| 回答生成 | 最终输出 | 主要输出成本 |

**关键派生指标**：
- `TokensPerSuccess = Σ tokens / 成功用例数`（含失败重试的真实成本）。
- `ReAct 轮次成本`：`llm_call_count` 与 `tool_call_count` 的均值，识别过度循环。

#### 4.1.3 任务耗时

**主指标**：平均端到端耗时（`total_duration_ms`）。

```
AvgLatency = Σ trace.total_duration_ms / N
```

- 报告 **P50 / P95 / Max**；**端到端耗时 ≈ LLM 耗时 + 工具耗时 + 检索耗时**，需给出三项占比，定位瓶颈（工具慢 / 模型慢 / 检索慢）。
- 工具耗时 = Σ `agent_tool_call_record.duration_ms`；LLM 耗时 = Σ `agent_llm_call_record.duration_ms`；检索耗时 = 端到端 − 前两者。
- 如启用 A4 埋点，额外报告 **TTFT（首 token 时延）**，评估流式体感。

#### 4.1.4 回答质量得分（LLM-as-Judge）

**评分方式**：固定 Judge 模型（建议用强模型，与 Agent 主模型解耦，如 Qwen-Max），固定 rubric 与 temperature=0，输出结构化 JSON。

**评分维度（1~5 分）与权重**：

| 维度 | 权重 | 评分要点 |
| --- | --- | --- |
| 正确性 Correctness | 30% | 结论/数据是否与工具返回、知识库一致，无幻觉 |
| 完整性 Completeness | 20% | 是否覆盖参考要点（key points），无关键遗漏 |
| 相关性 Relevance | 15% | 是否切题，无冗余跑题内容 |
| 可操作性 Actionability | 20% | 是否给出可执行建议（运维场景核心） |
| 安全合规 Safety | 10% | 是否越权执行写操作、是否泄露敏感信息 |
| 格式规范 Format | 5% | 是否符合 `Prompt/OutputRulesPrompt.md`（结构、Markdown、告警格式） |

```
QualityScore = Σ (维度得分 × 权重)          /* 归一化到 1~5，可再折算百分制 */
```

**Judge 输入（`POST /eval/judge`）**：`query` / `answer` / `keyPoints` / `toolCalls`。
`toolCalls` 是运行时从 `/agent/observability/traces/{traceId}` 采集的**实际工具调用序列**
（`toolName` / `toolInput` / `success`），不是回答正文的转述。必须传该字段的原因：正文未必把
调用细节写全，只有 `query+answer` 时 Judge 会凭散文推断，把"先下钻取证再作答"误判成"未调用工具"，
系统性地压低诊断类用例的分数（v3 首次全量就踩到：`db_diagnosis` 组规则 TSR 100%，质量分仅 3.22）。

rubric 中据此明确三条口径：**以工具调用清单为准**（不得仅凭正文推断调用与否）、
**工具成功返回但结果为空属正常事实**（如实说"没查到"不算不完整、不算编造）、
**清单为空却该取证作答则扣分**。改动 rubric 时同步递增 `EvalJudgeService.RUBRIC_VERSION`。

**Judge 可靠性保障**：
1. Judge Prompt 与 rubric **冻结版本化**，随测评结果一同记录（当前 `judge-rubric-v2`）。
2. 抽检 **10%~20%** 用例做人工评分，计算 Judge vs 人工的 **Spearman 秩相关 / Cohen's Kappa**，低于阈值（κ<0.6）则修订 rubric。
3. 关键用例支持 **pairwise 对比**（A/B 两版 Agent 输出，判优胜），比绝对打分更稳定。
4. Judge 与 Agent 使用不同模型，避免同源偏差；同一用例可跑 3 次取均值降噪。

---

### 4.2 复杂任务工具调用测评

**数据源**：`agent_tool_call_record`（`tool_name` / `tool_input` / `success` / `duration_ms`）+ `agent_conversation_trace`。

#### 4.2.1 工具调用成功率（是否调用到预期工具）

**定义**：将一次任务的实际调用集合 `A = {tool_name...}` 与期望集合 `E = {expected_tools...}` 比对。

| 指标 | 公式 | 含义 |
| --- | --- | --- |
| 工具选择准确率 Selection Accuracy | `1[ A == E ]` | 完全命中（集合相等） |
| 精确率 Precision | `|A∩E| / |A|` | 是否**误调用**不该用的工具 |
| 召回率 Recall | `|A∩E| / |E|` | 是否**漏调用**必需工具 |
| F1 | `2PR/(P+R)` | 综合选择质量 |
| 误调用率 | `|A−E| / |A|` | 主要错误来源，含 `hard_negative` 用例的"越界调用" |
| 调用顺序正确率 | 有序子序列匹配 | 仅 `order_sensitive=true` 的多工具用例统计（如"先诊断后告警"） |

对 `hard_negative` 用例（期望 `E=∅`），单独报告 **越界调用率**——这是 Agent 可靠性的关键红线。

#### 4.2.2 参数正确率

逐次工具调用，将 `tool_input`（JSON）与用例 `expected_params` 比对，分三层校验：

| 层级 | 校验内容 | 指标 | 说明 |
| --- | --- | --- | --- |
| L1 参数个数 | 键集合是否一致 | 个数正确率 | 有无缺失、有无多余 |
| L2 参数类型 | 值类型是否符合 `@P` 声明 | 类型正确率 | 如 `mode` 应为枚举、`instance` 应为 `host:port` |
| L3 参数取值 | 值是否等于期望 | 取值正确率 | 关键字段（instance / database / sql / mode）逐字段比对 |
| **L4 参数溯源** | 值是否**来源于用户输入或上下文** | 溯源合规率 / 幻觉率 | **重点**：值必须能在本用例 query 或历史多轮上下文中找到；编造的实例地址、库名、SQL 记为幻觉 |

```
参数正确率 = L1..L3 全通过的调用次数 / 总调用次数        /* 完全正确才算通过 */
参数级 F1  = 按 (工具名, 参数名, 值) 三元组计算的微观 F1  /* 部分正确给部分分 */
幻觉率     = 掺杂虚构参数值的调用次数 / 总调用次数        /* L4，越低越好 */
```

- L4 实现建议：对每个参数值做**包含性检查**（在 query / 上下文文本中做子串或模糊匹配），配合 LLM 复核歧义项；对 `__ASK_USER__` 期望用例，检查 Agent 是否**发起追问**而非编造。
- 参数正确率的失败明细必须保存 `tool_input` 原文，便于回溯。

#### 4.2.3 成本消耗

复用 4.1.2 / 4.1.3，但**按工具调用类用例分组**统计：

| 指标 | 说明 |
| --- | --- |
| 平均 Token / 工具调用任务 | 与整体对比，衡量 ReAct 的额外开销 |
| `tool_call_count` 均值 | 多工具任务的实际调用步数 vs 期望步数 |
| 单工具平均耗时 | 来自 `agent_tool_call_record.duration_ms`，定位慢工具 |
| 工具成功率 | `success=true` 占比（与"选对工具"不同，衡量**执行是否成功**） |

> 区分两类失败：**选错工具**（4.2.1）与**选对但执行失败**（`success=false`），后者看 `tool_output` 错误信息。

---

### 4.3 RAG 测评

**前提改造**：实现 [A1](#二测评总体架构) 暴露各阶段中间结果，测评服务需能拿到：

```
向量路候选  V = [chunkId...]        (pgvector, 当前 top100)
关键词路候选 K = [chunkId...]       (ES, 当前 top100)
融合结果   RRF = merge(V, K)        (RrfMerger, k=60, 输出 80)
重排结果   RR = rerank(query, RRF)  (llmService.rerank, 当前 topK)
```

对应代码：`KbRetrievalServiceImpl.retrieve`、`TextChunkVectorMapper.selectTopKWithDistance`、`KeywordSearchService.searchChunkIds`、`RrfMerger.merge`、`llmService.rerank`。

#### 4.3.1 整体召回率（端到端）

对最终返回结果（`RetrievedChunk`）比对 qrels：

| 指标 | 定义 |
| --- | --- |
| Recall@k | 相关文档中被召回到 top-k 的比例（k=1,3,5,10,20） |
| Precision@k | top-k 中相关文档占比 |
| Hit@k | top-k 是否至少命中一个相关文档 |
| MRR | 首个相关文档排名倒数均值 |
| nDCG@k | 考虑排序位置与相关度分级的增益 |

- **文档粒度**：chunk 命中后映射到 `file_id` 去重（复用 `BeirScifactDocRetrievalService` 的文档级融合逻辑）。
- **语料双轨**：① BEIR SciFact（英文，验证链路正确性、可对标公开基线）；② **自建中文运维集**（真实场景，主结论以此为准）。SciFact 结果仅作链路健康度参考，**不得单独作为召回质量结论**。

#### 4.3.2 双路召回阶段召回率

在**不经过 RRF 融合**的前提下，分别计算每条通路的召回，定位瓶颈：

| 指标 | 计算对象 | 诊断价值 |
| --- | --- | --- |
| 向量路 Recall@k | V | 语义召回能力（同义词、改写问题） |
| 关键词路 Recall@k | K | 精确术语/报错码召回能力 |
| 并集 Recall@k（召回上限） | V ∪ K | 融合前的理论上限，判断"是否根本没召回" |
| 融合后 Recall@k | RRF | 相较上限的**召回损失率**，评估融合是否掉召回 |

**核心诊断问题**：
1. **召回上限是否够**：若 `V∪K` 的 Recall@20 已低，问题在召回源（切分粒度/embedding/ES 分词），而非融合或重排。
2. **哪一路是短板**：向量路 vs 关键词路各自 Recall 差值，决定优化方向。
3. **融合是否损召回**：`RRF Recall` vs `V∪K Recall` 的差距。
4. 分阶段**召回漏斗**报告（示例）：

```
查询数 100 │ 相关文档总数 380
  ├─ 向量路 top100 命中  274  (72.1%)
  ├─ 关键词路 top100 命中 196  (51.6%)
  ├─ 并集(上限)          322  (84.7%)
  └─ RRF 融合 top80 命中  305  (80.3%)   ← 较上限损失 4.4pt
```

#### 4.3.3 重排序阶段（相关文档是否排名靠前）

**目标**：验证重排序（rerank）是否把相关文档往前提。做 **RRF 融合后 vs rerank 后**的配对对比：

| 指标 | 对比对象 | 期望 |
| --- | --- | --- |
| nDCG@10 | RRF vs Rerank | 重排后**提升** |
| MRR | RRF vs Rerank | 重排后**提升** |
| Recall@10 | RRF vs Rerank | 不应显著下降（若降，说明重排过激） |
| Top1 命中率 | Rerank 结果 | 第一条即相关 |
| Top3 命中率 | Rerank 结果 | 前三条含相关 |
| 相关文档平均排名 Mean Rank | RRF vs Rerank | 排名数值**下降**（更靠前） |
| 平均排名提升 ΔRank | Mean Rank(RRF) − Mean Rank(Rerank) | >0 表示重排有效 |

补充：
- **重排稳定性**：重排依赖 LLM，需固定模型与参数，重复 3 次报告方差，避免排序抖动。
- **重排耗时**：rerank 是额外 LLM 调用，记录耗时与 token，评估"质量提升 vs 成本"是否划算。
- **排序相关性**：报告 nDCG@10 相对提升百分比，作为重排模块 ROI 的核心论据。

---

## 五、执行方案（Eval Harness）

### 5.1 技术选型建议

**推荐：Python 编排 + Java 埋点/接口**，理由：指标计算、LLM Judge、报告生成在 Python 生态更灵活；项目已有 Python 评测脚本先例（`target/diag/*.py`）。Java 侧提供稳定的数据接口。

| 层 | 实现载体 | 职责 |
| --- | --- | --- |
| 数据暴露 | Java（新增 `eval` 包 / A1 改造） | 检索阶段中间结果、工具调用明细、trace 汇总 |
| 编排执行 | Python `eval/runner.py` | 读测试集、并发驱动接口、采集结果、落库 |
| 指标计算 | Python `eval/metrics/` | TSR / Token / Latency / 工具 / 召回 指标 |
| LLM Judge | Python `eval/judge.py` | 回答质量打分、PASS/FAIL 判定 |
| 报告 | Python `eval/report.py` | 生成 Markdown/HTML 报告 |

### 5.2 执行流程

```
1. 准备：固定 Agent 版本(git commit)、模型版本、Judge 版本、测试集版本，生成 run_id
2. 冷启动：重启后端，清空/标记本次 run 的 trace（避免历史数据污染）
3. 驱动：按 case 逐条调用 /agent/chat/reactive/stream，记录 trace_id，解析 SSE
4. 采集：轮询 GET /agent/observability/traces/{traceId} 拿 Token / 耗时 / 工具明细
5. 判定：规则比对 + LLM Judge 打分
6. 落库：写入 agent_eval_run / agent_eval_case_result
7. 报告：汇总指标 + 分组维度 + 失败用例明细 + 与基线 run 对比
```

### 5.3 可复现性要求（必须）

| 项 | 要求 |
| --- | --- |
| 模型与参数 | `temperature=0`（Judge 必须），记录模型版本号 |
| 测试集版本 | 文件名内嵌版本，报告记录 |
| 冷启动 | 每次 run 前重启后端，或按时间窗过滤 trace |
| 重复次数 | 关键指标跑 ≥ 3 次，报均值 ± 标准差 |
| 环境 | 中间件（PG/ES/Redis/MinIO/RocketMQ）健康，知识库版本一致 |
| 隔离 | 测评**不得触发真实写操作**：WRITE 工具（`resetSlowQueryStats` / `sendAlertEmail`）用测试收件人或 mock 端点；`db_diagnosis` 诊断组新增的 6 个工具全部为只读，不涉及该清单 |

### 5.4 新增结果表设计（A3）

```sql
/* 测评批次：一次完整跑批 */
CREATE TABLE agent_eval_run (
    id              BIGSERIAL PRIMARY KEY,
    run_id          VARCHAR(64)  NOT NULL UNIQUE,   /* eval_20260914_1530 */
    suite_name      VARCHAR(64)  NOT NULL,          /* chat / tool / rag */
    dataset_version VARCHAR(64),
    agent_commit    VARCHAR(64),                    /* git commit */
    model_version   VARCHAR(64),
    judge_version   VARCHAR(64),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    total_cases     INT,
    success_cases   INT,
    avg_tokens      NUMERIC(12,2),
    avg_duration_ms NUMERIC(12,2),
    summary_json    JSONB,                          /* 全量指标快照 */
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

/* 单用例结果：支撑下钻与回归对比 */
CREATE TABLE agent_eval_case_result (
    id              BIGSERIAL PRIMARY KEY,
    run_id          VARCHAR(64) NOT NULL,
    case_id         VARCHAR(64) NOT NULL,
    category        VARCHAR(32),
    difficulty      VARCHAR(16),
    trace_id        VARCHAR(64),                    /* 关联可观测三表 */
    task_success    BOOLEAN,
    tool_selection  JSONB,                          /* {expected, actual, P, R, F1} */
    param_result    JSONB,                          /* {count_ok, type_ok, value_ok, grounded, hallucination} */
    retrieval_result JSONB,                         /* {vector_hit, keyword_hit, rrf_hit, rerank_hit, ranks} */
    quality_scores  JSONB,                          /* {correctness:5, completeness:4, ...} */
    total_tokens    INT,
    duration_ms     INT,
    judge_reason    TEXT,
    raw_output      TEXT,
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_eval_case_run ON agent_eval_case_result(run_id, case_id);
```

> 与 OSS 项目约定一致：新增表 DDL 放入 `src/main/resources/db/`（参考 `agent_long_term_memory.sql` 的写法）。

---

## 六、报告设计

测评报告需包含以下内容，输出至 `docs/eval-reports/eval_<run_id>.md`：

1. **元信息**：run_id、Agent commit、模型/Judge 版本、测试集版本、执行时间、环境。
2. **总览看板**：三大模块核心指标卡片 + 与上次基线的 **Δ 变化**（回归检测）。
3. **整体测评**：TSR、Token（均值/P95/分层）、耗时（均值/P95/瓶颈三分解）、质量得分（六维雷达图）。
4. **工具调用测评**：Selection P/R/F1、越界调用率、参数三层正确率 + 幻觉率、分组（单工具/多工具/硬负例）明细。
5. **RAG 测评**：整体 Recall@k / nDCG@k 曲线、双路召回漏斗、重排前后配对对比表、耗时成本。
6. **失败分析**：Top-N 失败用例清单（case_id + 原因 + trace_id + 原文），按根因聚类（选错工具 / 参数幻觉 / 召回缺失 / 生成跑偏）。
7. **结论与改进建议**：按"召回→重排→路由→生成"链路给出优化优先级。

---

## 七、实施步骤与优先级

| 阶段 | 工作内容 | 前置 | 优先级 |
| --- | --- | --- | --- |
| P0 基础设施 | A1 暴露检索阶段中间结果；A3 建结果表；确认 `tool_input` 序列化字段名 | 无 | 高 |
| P0 数据准备 | 构建三套测试集（chat/tool/rag），含中文运维集标注 | 无 | 高 |
| P1 工具测评 | 工具选择 + 参数三层 + 溯源校验（规则判定，无需 LLM，最快见效） | P0 | 高 |
| P1 整体测评 | TSR 规则判定 + Token/耗时统计（复用可观测表，零埋点成本） | P0 | 高 |
| P1 RAG 测评 | 召回率 + 双路漏斗 + 重排对比（复用 BEIR 脚本扩展） | A1 | 高 |
| P2 质量打分 | LLM-as-Judge rubric + 人工抽检校准 | P1 | 中 |
| P2 回归基建 | run 对比、CI 接入、报告自动化 | 全部 | 中 |
| P3 体验指标 | TTFT、重排/多路召回的成本收益分析 | A4 | 低 |

---

## 八、风险与注意事项

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| 双路召回中间结果不可得 | RAG 分阶段测评无法落地 | **A1 为前置必做**，新增 `RagStageTrace`，不侵入生产逻辑（旁路字段） |
| SciFact 为英文、非运维域 | 召回结论失真 | 以自建中文运维集为主结论，SciFact 仅验链路 |
| LLM Judge 与人工不一致 | 质量分不可信 | 抽检 10%~20%，κ<0.6 则修 rubric；关键用例走 pairwise |
| WRITE 工具误触发 | 污染真实数据 / 误发邮件 | 测评环境隔离，邮件走测试收件人，重置类工具打标禁用或用专用实例 |
| 历史 trace 污染统计 | 指标虚高/虚低 | 按 run 时间窗过滤，或每次 run 前冷启动 |
| 检索/LLM 波动 | 指标不可复现 | temperature=0，重复 3 次取均值，固定知识库版本 |
| 成本随测评规模放大 | LLM Judge + 多轮 ReAct 费用高 | 分层抽样先行（小样本快跑），全量测评定期执行 |

---

## 九、附录

### 9.1 工具与期望参数速查（用于构建 `tool_cases`）

| 工具（`@Tool` 简称） | 参数 | 类型 | WRITE |
| --- | --- | --- | --- |
| `checkDatabaseHealth` | `instanceName` | 业务名（如"rag库"） | 否 |
| `collectDatabaseMetrics` | `instance`, `database` | `host:port`, 库名 | 否 |
| `getTopSlowQueries` | `instance`, `database` | `host:port`, 库名 | 否 |
| `resetSlowQueryStats` | `instance`, `database` | `host:port`, 库名 | **是** |
| `getSqlExecutionPlan` | `instance`, `database`, `sql`, `mode` | `host:port`, 库名, SQL 文本, `estimated\|actual` | 否 |
| `sendAlertEmail` | `instanceName`, `runId`, `subject`, `content` | 业务名, 运行ID, 主题, 正文 | **是** |
| `getWeather` | `city` | 城市名 | 否 |
| `listActiveSessions` | `instance`, `database`(选) | 业务名或 `host:port`, 库名 | 否 |
| `getWaitEventDistribution` | `instance`, `database`(选) | 业务名或 `host:port`, 库名 | 否 |
| `getBlockingChains` | `instance`, `database`(选) | 业务名或 `host:port`, 库名 | 否 |
| `getReplicationStatus` | `instance`, `database`(选) | 业务名或 `host:port`, 库名 | 否 |
| `getVacuumAndBloatStatus` | `instance`, `database`(选) | 业务名或 `host:port`, 库名 | 否 |
| `getTableAccessStats` | `instance`, `database`(选) | 业务名或 `host:port`, 库名 | 否 |

> 表末 6 行为 `db_diagnosis` 任务的下钻工具，全部**只读**。`instance` 同时接受巡检配置中的业务名（如 `rag库`）与 `host:port` 字面地址：用业务名时可省略 `database`（自动回填该实例配置的库名），用 `host:port` 时 `database` 必填。多步下钻时后续步骤的 `instance`/`database` 直接复用上一步的值，无需用户重复提供；`argument_sources` 按**值的实际来处**标注——值逐字在用户本轮原话里 → `EXPLICIT_CURRENT`，值取自本轮此前成功的工具返回结果 → `TOOL_OUTPUT`。

### 9.2 指标速查表

| 模块 | 指标 | 数据来源 |
| --- | --- | --- |
| 整体 | 任务成功率 TSR | 规则 + LLM Judge |
| 整体 | Token 成本（均值/P95/分层） | `agent_conversation_trace` + `agent_llm_call_record` |
| 整体 | 耗时（均值/P95/瓶颈分解） | `agent_conversation_trace` + 子表 |
| 整体 | 回答质量得分（六维加权） | LLM-as-Judge |
| 工具 | 工具选择 P/R/F1、越界调用率 | `agent_tool_call_record.tool_name` |
| 工具 | 参数个数/类型/取值正确率、幻觉率 | `agent_tool_call_record.tool_input` |
| 工具 | 成本、单工具耗时、执行成功率 | `agent_tool_call_record` |
| RAG | 整体 Recall@k / nDCG@k / MRR | qrels + 最终结果 |
| RAG | 双路召回 Recall@k、融合损失率 | A1 阶段中间结果 |
| RAG | 重排前后 nDCG@10 / MRR / Mean Rank | A1 阶段中间结果 |

### 9.3 关键代码位置索引

| 用途 | 位置 |
| --- | --- |
| 检索链路（双路召回 + 融合 + 重排） | `src/main/java/com/library/agent/rag/KbRetrievalServiceImpl.retrieve` |
| RRF 融合 | `src/main/java/com/library/agent/rag/RrfMerger.merge` |
| 向量检索 | `TextChunkVectorMapper.selectTopKWithDistance` |
| 关键词检索 | `KeywordSearchService.searchChunkIds` |
| 可观测采集 | `observability/ConversationTraceCollector`、`ConversationTraceService` |
| 可观测查询 | `GET /agent/observability/traces/{traceId}` |
| 工具实现 | `src/main/java/com/library/agent/tool/*` |
| BEIR 检索评测 | `/eval/beir/scifact/*`，语料 `beir_scifact_eval/` |
| 输出规范（Judge Format 维度参照） | `src/main/resources/Prompt/OutputRulesPrompt.md` |
