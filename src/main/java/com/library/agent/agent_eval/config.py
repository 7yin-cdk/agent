import os

BASE_URL = os.environ.get("AGENT_BASE_URL", "http://localhost:8084").rstrip("/")
USERNAME = os.environ.get("AGENT_USERNAME", "")
PASSWORD = os.environ.get("AGENT_PASSWORD", "")

DATASET = os.environ.get("AGENT_EVAL_DATASET", "datasets/chat_cases_v3.jsonl")
DATASET_VERSION = os.environ.get("AGENT_EVAL_DATASET_VERSION", "v3_20260917")
SUITE_NAME = os.environ.get("AGENT_EVAL_SUITE", "CHAT")
RUN_ID = os.environ.get("AGENT_EVAL_RUN_ID", "")

MODEL_VERSION = os.environ.get("AGENT_EVAL_MODEL_VERSION", "")
JUDGE_MODEL = os.environ.get("AGENT_EVAL_JUDGE_MODEL", "")

LIMIT = int(os.environ.get("AGENT_EVAL_LIMIT", "0"))
JUDGE_ENABLED = os.environ.get("AGENT_EVAL_JUDGE", "1").lower() not in ("0", "false", "no")
TIMEOUT_SECONDS = int(os.environ.get("AGENT_EVAL_TIMEOUT", "180"))
REPORT_DIR = os.environ.get("AGENT_EVAL_REPORT_DIR", "reports")

TOOL_DATASET = os.environ.get("AGENT_EVAL_TOOL_DATASET", "datasets/tool_cases_v1.jsonl")
TOOL_DATASET_VERSION = os.environ.get("AGENT_EVAL_TOOL_DATASET_VERSION", "tool_v1_20260926")
TOOL_SUITE_NAME = os.environ.get("AGENT_EVAL_TOOL_SUITE", "TOOL")
TOOL_REPORT_DIR = os.environ.get("AGENT_EVAL_TOOL_REPORT_DIR", "reports/tool")

RAG_DATASET = os.environ.get("AGENT_EVAL_RAG_DATASET", "datasets/rag_cases_v1.jsonl")
RAG_DATASET_VERSION = os.environ.get("AGENT_EVAL_RAG_DATASET_VERSION", "rag_v1_20260926_qg1")
RAG_SUITE_NAME = os.environ.get("AGENT_EVAL_RAG_SUITE", "RAG")
RAG_REPORT_DIR = os.environ.get("AGENT_EVAL_RAG_REPORT_DIR", "reports/rag")

KB_SNAPSHOT = os.environ.get("AGENT_EVAL_KB_SNAPSHOT", "datasets/kb_chunks_snapshot.jsonl")
KB_DOC_PAGE_SIZE = int(os.environ.get("AGENT_EVAL_KB_DOC_PAGE_SIZE", "100"))
KB_CHUNK_PAGE_SIZE = int(os.environ.get("AGENT_EVAL_KB_CHUNK_PAGE_SIZE", "200"))

RAG_TOPK = int(os.environ.get("AGENT_EVAL_RAG_TOPK", "50"))
RAG_RERANK = os.environ.get("AGENT_EVAL_RAG_RERANK", "1").lower() not in ("0", "false", "no")


def require_credentials():
    missing = [name for name, value in (("AGENT_USERNAME", USERNAME), ("AGENT_PASSWORD", PASSWORD)) if not value]
    if missing:
        raise SystemExit("缺少登录凭据，请先设置环境变量：" + ", ".join(missing))
