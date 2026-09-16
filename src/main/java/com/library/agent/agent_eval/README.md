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
| `datasets/chat_cases_v1.jsonl` | 测试集，80 条 |
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
| `AGENT_EVAL_DATASET` | `datasets/chat_cases_v1.jsonl` | 测试集路径（相对当前工作目录） |
| `AGENT_EVAL_DATASET_VERSION` | `v1_20260914` | 写入批次的测试集版本 |
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
 "query":"什么是数据库的缓冲池命中率？","key_points":[],"notes":"",
 "history":["上一轮用户输入"]}
```

- `history` 可选，仅多轮指代用例使用；存在时先建会话并重放历史轮次再接末轮。
- `key_points` 供 Judge 参考；为空时 Judge 走 reference-free 打分。
- 告警类（`sendAlertEmail`）用例会触发写工具，评测环境需确保收件人为测试地址。
