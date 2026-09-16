import json
import subprocess
import sys

import client as api
import config
import metrics
import tsr

FLUSH_EVERY = 10

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def load_cases(path):
    cases = []
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                cases.append(json.loads(line))
    return cases


def git_commit():
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            capture_output=True,
            timeout=10,
        )
        if result.returncode == 0:
            return result.stdout.decode("utf-8").strip()
    except Exception:
        pass
    return ""


def run_case(agent, case):
    history = case.get("history") or []
    conversation_id = agent.create_conversation(case["case_id"]) if history else None
    for turn in history:
        agent.chat_stream(turn, conversation_id)
    return agent.chat_stream(case["query"], conversation_id)


def summarize_trace(detail):
    llm_calls = (detail or {}).get("llmCalls") or []
    tool_calls = (detail or {}).get("toolCalls") or []
    by_type = {}
    for call in llm_calls:
        key = call.get("callType") or "UNKNOWN"
        bucket = by_type.setdefault(
            key, {"count": 0, "inputTokens": 0, "outputTokens": 0, "durationMs": 0}
        )
        bucket["count"] += 1
        bucket["inputTokens"] += call.get("inputTokens") or 0
        bucket["outputTokens"] += call.get("outputTokens") or 0
        bucket["durationMs"] += call.get("durationMs") or 0
    return {
        "tokenByCallType": by_type,
        "llmDurationMs": sum(call.get("durationMs") or 0 for call in llm_calls),
        "toolDurationMs": sum(call.get("durationMs") or 0 for call in tool_calls),
        "toolNames": [call.get("toolName") for call in tool_calls],
        "toolFailures": [
            call.get("toolName") for call in tool_calls if call.get("success") is False
        ],
    }


def judge_answer(agent, case, chat):
    if not config.JUDGE_ENABLED:
        return None
    answer = (chat.get("answer") or "").strip()
    if not answer:
        return None
    try:
        return agent.judge(case["query"], answer, case.get("key_points") or [])
    except Exception as exc:
        print(f"    [judge] 打分失败：{exc}")
        return None


def build_payload(case, chat, trace, success, reason, judged):
    payload = {
        "caseId": case["case_id"],
        "category": case.get("category"),
        "difficulty": case.get("difficulty"),
        "query": case["query"],
        "traceId": chat.get("traceId"),
        "taskSuccess": success,
        "successReason": reason,
        "rawOutput": chat.get("answer") or "",
        "errorMessage": chat.get("error"),
        "totalInputTokens": trace.get("totalInputTokens"),
        "totalOutputTokens": trace.get("totalOutputTokens"),
        "totalTokens": trace.get("totalTokens"),
        "durationMs": trace.get("totalDurationMs"),
        "llmCallCount": trace.get("llmCallCount"),
        "toolCallCount": trace.get("toolCallCount"),
    }
    if judged:
        payload["qualityScores"] = judged.get("scores")
        payload["qualityTotal"] = judged.get("total")
        payload["judgeReason"] = judged.get("reason")
    return payload


def build_detail(case, trace, trace_detail):
    detail = {
        "caseId": case["case_id"],
        "category": case.get("category"),
        "difficulty": case.get("difficulty"),
        "intentType": trace.get("intentType"),
        "status": trace.get("status"),
    }
    detail.update(summarize_trace(trace_detail))
    return detail


def evaluate_case(agent, case):
    chat = run_case(agent, case)
    trace_detail = agent.get_trace(chat["traceId"]) if chat.get("traceId") else None
    trace = (trace_detail or {}).get("trace") or {}
    success, reason = tsr.judge_task_success(chat, trace)
    judged = judge_answer(agent, case, chat)
    payload = build_payload(case, chat, trace, success, reason, judged)
    return payload, build_detail(case, trace, trace_detail)


def script_error(case, exc):
    payload = {
        "caseId": case["case_id"],
        "category": case.get("category"),
        "difficulty": case.get("difficulty"),
        "query": case["query"],
        "taskSuccess": False,
        "successReason": "评测脚本异常：" + repr(exc),
        "errorMessage": repr(exc),
    }
    detail = {
        "caseId": case["case_id"],
        "category": case.get("category"),
        "difficulty": case.get("difficulty"),
        "intentType": None,
        "status": "SCRIPT_ERROR",
        "tokenByCallType": {},
        "llmDurationMs": 0,
        "toolDurationMs": 0,
        "toolNames": [],
        "toolFailures": [],
    }
    return payload, detail


def main():
    config.require_credentials()
    cases = load_cases(config.DATASET)
    if config.LIMIT > 0:
        cases = cases[: config.LIMIT]
    if not cases:
        raise SystemExit("测试集为空：" + config.DATASET)

    agent = api.AgentClient()
    agent.login()
    rubric_version = agent.judge_version().get("rubricVersion")
    created = agent.create_run(
        {
            "runId": config.RUN_ID or None,
            "suiteName": config.SUITE_NAME,
            "datasetVersion": config.DATASET_VERSION,
            "agentCommit": git_commit(),
            "modelVersion": config.MODEL_VERSION,
            "judgeVersion": config.JUDGE_MODEL,
            "judgePromptVersion": rubric_version,
        }
    )
    run_id = created.get("runId")
    print(f"[run] {run_id}  用例 {len(cases)} 条  judge={config.JUDGE_ENABLED}")

    pending = []
    details = []
    for index, case in enumerate(cases, 1):
        try:
            payload, detail = evaluate_case(agent, case)
        except Exception as exc:
            payload, detail = script_error(case, exc)
        pending.append(payload)
        details.append(detail)
        flag = "OK  " if payload.get("taskSuccess") else "FAIL"
        print(f"[{index}/{len(cases)}] {case['case_id']} {flag} {payload.get('successReason') or ''}")
        if len(pending) >= FLUSH_EVERY:
            agent.save_cases(run_id, pending)
            pending = []
    if pending:
        agent.save_cases(run_id, pending)

    view = agent.finish_run(run_id, "FINISHED")
    report_path = metrics.write_report(run_id, view, details)
    print(f"[report] {report_path}")


if __name__ == "__main__":
    main()
