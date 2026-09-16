FALLBACK_MARKERS = (
    "未能从现有能力中匹配到可执行模块",
    "暂时不可用，请稍后再试",
    "系统暂时无法处理您的请求",
    "AI 服务暂时不可用",
    "Tool task finished, but no answer was returned",
)


def judge_task_success(chat, trace):
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
    for marker in FALLBACK_MARKERS:
        if marker in answer:
            return False, "命中系统兜底文案：" + marker
    return True, "ok"
