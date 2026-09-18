NO_MATCH_MARKER = "未能从现有能力中匹配到可执行模块"

FAILURE_MARKERS = (
    "暂时不可用，请稍后再试",
    "系统暂时无法处理您的请求",
    "AI 服务暂时不可用",
    "Tool task finished, but no answer was returned",
    "本次请求缺少完成任务所必需的参数信息",
)


def judge_task_success(case, chat, trace):
    if chat.get("error"):
        return False, "SSE error: " + str(chat["error"])
    if trace is None:
        return False, "可观测 trace 缺失，无法确认执行结果"
    status = trace.get("status")
    if status != "SUCCESS":
        message = trace.get("errorMessage") or ""
        return False, f"trace status={status} {message}".strip()
    answer = (chat.get("answer") or "").strip()
    if not answer:
        return False, "回答为空"
    for marker in FAILURE_MARKERS:
        if marker in answer:
            return False, "命中系统兜底文案：" + marker
    hit_no_match = NO_MATCH_MARKER in answer
    if case.get("expect_no_match"):
        if hit_no_match:
            return True, "ok（符合预期的无匹配回退）"
        return False, "期望无匹配回退，但未命中：" + NO_MATCH_MARKER
    if hit_no_match:
        return False, "命中系统兜底文案：" + NO_MATCH_MARKER
    return True, "ok"
