import json
import os
import sys

import config

DRAFT_PATH = os.environ.get("AGENT_EVAL_RAG_DRAFT", "datasets/rag_cases_draft.jsonl")

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


def main():
    cases = load_cases(DRAFT_PATH)
    for case in cases:
        case["reviewed"] = True
    with open(config.RAG_DATASET, "w", encoding="utf-8") as handle:
        for case in cases:
            handle.write(json.dumps(case, ensure_ascii=False) + "\n")
    print(f"[freeze] {DRAFT_PATH} -> {config.RAG_DATASET} 共 {len(cases)} 条，reviewed 已置 true")


if __name__ == "__main__":
    main()
