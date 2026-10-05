import sys

import client as api
import config
import runner
import tool_metrics
import tsr

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

FLUSH_EVERY = 10


def build_payload(case, chat, trace, success, reason, judged, selection, params):
    payload = runner.build_payload(case, chat, trace, success, reason, judged)
    payload["intentType"] = trace.get("intentType")
    payload["toolSelection"] = selection
    payload["paramResult"] = params
    return payload


def build_detail(case, trace, trace_detail, tsr_ok, selection, params):
    detail = runner.build_detail(case, trace, trace_detail)
    detail["tsrOk"] = tsr_ok
    detail["expectedIntent"] = case.get("expected_intent")
    detail["toolSelection"] = selection
    detail["paramResult"] = params
    return detail


def evaluate_case(agent, case, schema):
    chat = runner.run_case(agent, case)
    trace_detail = agent.get_trace(chat["traceId"]) if chat.get("traceId") else None
    trace = (trace_detail or {}).get("trace") or {}
    tsr_ok, tsr_reason = tsr.judge_task_success(case, chat, trace)
    judged = runner.judge_answer(agent, case, chat, trace_detail)
    success, reason, selection, params = tool_metrics.evaluate_case(
        case, trace_detail, schema, tsr_ok, tsr_reason
    )
    payload = build_payload(case, chat, trace, success, reason, judged, selection, params)
    detail = build_detail(case, trace, trace_detail, tsr_ok, selection, params)
    return payload, detail


def script_error(case, exc):
    payload, detail = runner.script_error(case, exc)
    payload["intentType"] = None
    payload["toolSelection"] = None
    payload["paramResult"] = None
    detail["tsrOk"] = False
    detail["expectedIntent"] = case.get("expected_intent")
    return payload, detail


def main():
    config.require_credentials()
    cases = runner.load_cases(config.TOOL_DATASET)
    if config.LIMIT > 0:
        cases = cases[: config.LIMIT]
    if not cases:
        raise SystemExit("测试集为空：" + config.TOOL_DATASET)

    agent = api.AgentClient()
    agent.login()
    schema = tool_metrics.load_schema(agent.tool_schema())
    rubric_version = agent.judge_version().get("rubricVersion") if config.JUDGE_ENABLED else None

    created = agent.create_run(
        {
            "runId": config.RUN_ID or None,
            "suiteName": config.TOOL_SUITE_NAME,
            "datasetVersion": config.TOOL_DATASET_VERSION,
            "agentCommit": runner.git_commit(),
            "modelVersion": config.MODEL_VERSION,
            "judgeVersion": config.JUDGE_MODEL,
            "judgePromptVersion": rubric_version,
        }
    )
    run_id = created.get("runId")
    print("[run] %s  用例 %d 条  已注册工具 %d 个  judge=%s"
          % (run_id, len(cases), len(schema), config.JUDGE_ENABLED))

    pending = []
    details = []
    for index, case in enumerate(cases, 1):
        try:
            payload, detail = evaluate_case(agent, case, schema)
        except Exception as exc:
            payload, detail = script_error(case, exc)
        pending.append(payload)
        details.append(detail)
        flag = "OK  " if payload.get("taskSuccess") else "FAIL"
        print("[%d/%d] %s %s %s"
              % (index, len(cases), case["case_id"], flag, payload.get("successReason") or ""))
        if len(pending) >= FLUSH_EVERY:
            agent.save_cases(run_id, pending)
            pending = []
    if pending:
        agent.save_cases(run_id, pending)

    view = agent.finish_run(run_id, "FINISHED")
    case_index = {case["case_id"]: case for case in cases}
    report_path = tool_metrics.write_report(run_id, view, details, case_index)
    print("[report] %s" % report_path)


if __name__ == "__main__":
    main()
