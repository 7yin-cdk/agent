import os

BASE_URL = os.environ.get("AGENT_BASE_URL", "http://localhost:8084").rstrip("/")
USERNAME = os.environ.get("AGENT_USERNAME", "")
PASSWORD = os.environ.get("AGENT_PASSWORD", "")

DATASET = os.environ.get("AGENT_EVAL_DATASET", "datasets/chat_cases_v1.jsonl")
DATASET_VERSION = os.environ.get("AGENT_EVAL_DATASET_VERSION", "v1_20260914")
SUITE_NAME = os.environ.get("AGENT_EVAL_SUITE", "CHAT")
RUN_ID = os.environ.get("AGENT_EVAL_RUN_ID", "")

MODEL_VERSION = os.environ.get("AGENT_EVAL_MODEL_VERSION", "")
JUDGE_MODEL = os.environ.get("AGENT_EVAL_JUDGE_MODEL", "")

LIMIT = int(os.environ.get("AGENT_EVAL_LIMIT", "0"))
JUDGE_ENABLED = os.environ.get("AGENT_EVAL_JUDGE", "1").lower() not in ("0", "false", "no")
TIMEOUT_SECONDS = int(os.environ.get("AGENT_EVAL_TIMEOUT", "180"))
REPORT_DIR = os.environ.get("AGENT_EVAL_REPORT_DIR", "reports")


def require_credentials():
    missing = [name for name, value in (("AGENT_USERNAME", USERNAME), ("AGENT_PASSWORD", PASSWORD)) if not value]
    if missing:
        raise SystemExit("缺少登录凭据，请先设置环境变量：" + ", ".join(missing))
