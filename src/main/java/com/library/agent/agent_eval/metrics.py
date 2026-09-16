import os
from statistics import mean

import config

FAILURE_PREVIEW = 80


def fmt(value, digits=2):
    if value is None:
        return "-"
    if isinstance(value, (int, float)):
        return f"{value:.{digits}f}"
    return str(value)


def pct(value, digits=1):
    if value is None:
        return "-"
    return f"{value * 100:.{digits}f}%"


def table(headers, rows):
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join(["---"] * len(headers)) + " |",
    ]
    for row in rows:
        lines.append("| " + " | ".join(str(cell) for cell in row) + " |")
    return "\n".join(lines)


def preview(text, limit=FAILURE_PREVIEW):
    text = (text or "").replace("\n", " ").strip()
    return text if len(text) <= limit else text[:limit] + "…"


def group_rows(stats_map):
    rows = []
    for name, stats in (stats_map or {}).items():
        rows.append(
            [
                name,
                stats.get("total"),
                stats.get("success"),
                pct(stats.get("taskSuccessRate")),
                fmt(stats.get("avgTokens")),
                fmt(stats.get("avgDurationMs")),
                fmt(stats.get("avgQualityScore"), 3),
            ]
        )
    return rows


def token_rows(details):
    buckets = {}
    for detail in details:
        for call_type, stats in (detail.get("tokenByCallType") or {}).items():
            bucket = buckets.setdefault(
                call_type, {"count": 0, "inputTokens": 0, "outputTokens": 0, "durationMs": 0}
            )
            bucket["count"] += stats.get("count") or 0
            bucket["inputTokens"] += stats.get("inputTokens") or 0
            bucket["outputTokens"] += stats.get("outputTokens") or 0
            bucket["durationMs"] += stats.get("durationMs") or 0
    return sorted(
        buckets.items(),
        key=lambda item: item[1]["inputTokens"] + item[1]["outputTokens"],
        reverse=True,
    )


def build_report(run_id, view, details):
    run = view.get("run") or {}
    cases = view.get("cases") or []
    summary = run.get("summaryJson") or {}
    detail_map = {detail["caseId"]: detail for detail in details}

    total = run.get("totalCases") or 0
    success = run.get("successCases") or 0
    tsr_value = summary.get("taskSuccessRate")
    if tsr_value is None and total:
        tsr_value = success / total

    parts = [f"# Agent 整体测评报告 `{run_id}`\n"]

    parts.append("## 一、批次元信息\n")
    parts.append(
        table(
            ["项", "值"],
            [
                ["套件", run.get("suiteName")],
                ["测试集版本", run.get("datasetVersion")],
                ["Agent Commit", run.get("agentCommit")],
                ["模型版本", run.get("modelVersion") or "-"],
                ["Judge 模型", run.get("judgeVersion") or "-"],
                ["Judge Prompt 版本", run.get("judgePromptVersion")],
                ["开始时间", run.get("startedAt")],
                ["结束时间", run.get("finishedAt")],
            ],
        )
    )

    parts.append("\n## 二、总览\n")
    parts.append(
        table(
            ["指标", "值"],
            [
                ["用例总数", total],
                ["成功用例", success],
                ["任务成功率", pct(tsr_value)],
                ["平均 Token", fmt(run.get("avgTokens"))],
                ["P95 Token", fmt(run.get("p95Tokens"))],
                ["平均耗时(ms)", fmt(run.get("avgDurationMs"))],
                ["P95 耗时(ms)", fmt(run.get("p95DurationMs"))],
                ["质量均分(1-5)", fmt(run.get("avgQualityScore"), 3)],
                ["平均 LLM 调用次数", fmt(summary.get("avgLlmCallCount"))],
                ["平均工具调用次数", fmt(summary.get("avgToolCallCount"))],
            ],
        )
    )

    parts.append("\n## 三、按分类\n")
    parts.append(
        table(
            ["分类", "用例数", "成功", "成功率", "平均Token", "平均耗时(ms)", "质量均分"],
            group_rows(summary.get("byCategory")),
        )
    )

    parts.append("\n## 四、按难度\n")
    parts.append(
        table(
            ["难度", "用例数", "成功", "成功率", "平均Token", "平均耗时(ms)", "质量均分"],
            group_rows(summary.get("byDifficulty")),
        )
    )

    parts.append("\n## 五、Token 分层（按 LLM 调用类型）\n")
    parts.append(
        table(
            ["调用类型", "调用次数", "输入Token", "输出Token", "合计", "耗时(ms)"],
            [
                [
                    call_type,
                    stats["count"],
                    stats["inputTokens"],
                    stats["outputTokens"],
                    stats["inputTokens"] + stats["outputTokens"],
                    stats["durationMs"],
                ]
                for call_type, stats in token_rows(details)
            ],
        )
    )

    parts.append("\n## 六、耗时拆解\n")
    llm_durations = [detail.get("llmDurationMs") or 0 for detail in details]
    tool_durations = [detail.get("toolDurationMs") or 0 for detail in details]
    parts.append(
        table(
            ["口径", "平均耗时(ms)"],
            [
                ["端到端（trace.totalDurationMs）", fmt(run.get("avgDurationMs"))],
                ["Σ LLM 调用", fmt(mean(llm_durations) if llm_durations else None)],
                ["Σ 工具调用", fmt(mean(tool_durations) if tool_durations else None)],
            ],
        )
    )

    parts.append("\n## 七、失败用例明细\n")
    failures = [case for case in cases if not case.get("taskSuccess")]
    if not failures:
        parts.append("无失败用例。")
    else:
        parts.append(
            table(
                ["用例", "分类", "难度", "Trace 状态", "失败原因", "输出摘要"],
                [
                    [
                        case.get("caseId"),
                        case.get("category"),
                        case.get("difficulty"),
                        (detail_map.get(case.get("caseId")) or {}).get("status") or "-",
                        case.get("successReason") or case.get("errorMessage") or "-",
                        preview(case.get("rawOutput")),
                    ]
                    for case in failures
                ],
            )
        )

    parts.append("")
    return "\n".join(parts)


def write_report(run_id, view, details):
    os.makedirs(config.REPORT_DIR, exist_ok=True)
    name = run_id if run_id.startswith("eval_") else f"eval_{run_id}"
    path = os.path.join(config.REPORT_DIR, f"{name}.md")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(build_report(run_id, view, details))
    return path
