import json
import os
import random
import re
import sys

import client as api
import config

SEED = int(os.environ.get("AGENT_EVAL_RAG_SEED", "20260926"))
SEED_COUNT = int(os.environ.get("AGENT_EVAL_RAG_SEED_COUNT", "45"))
SEED_PER_DOC_CAP = int(os.environ.get("AGENT_EVAL_RAG_SEED_PER_DOC_CAP", "8"))
DRAFT_PATH = os.environ.get("AGENT_EVAL_RAG_DRAFT", "datasets/rag_cases_draft.jsonl")

ASCII_IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]{1,}")

DIFFICULTY_BY_TYPE = {
    "concept": "MEDIUM",
    "param": "EASY",
    "howto": "MEDIUM",
    "troubleshoot": "MEDIUM",
    "compare": "HARD",
}

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def load_snapshot(path):
    rows = []
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def depth_bucket(depth):
    if depth <= 1:
        return "d1"
    if depth == 2:
        return "d2"
    if depth == 3:
        return "d3"
    return "d4plus"


def sample_seeds(rows):
    usable = [
        row
        for row in rows
        if (row.get("chunk_text") or "").strip() and (row.get("section_path") or "").strip()
    ]
    by_file = {}
    for row in usable:
        by_file.setdefault(row.get("file_name"), []).append(row)

    rng = random.Random(SEED)
    for name in by_file:
        rng.shuffle(by_file[name])

    buckets = {"d1": [], "d2": [], "d3": [], "d4plus": []}
    for name in sorted(by_file):
        for row in by_file[name]:
            buckets[depth_bucket(row.get("section_depth") or 0)].append(row)

    seeds = []
    seen = set()
    per_file = {}
    order = ["d1", "d2", "d3", "d4plus"]
    cursor = {key: 0 for key in order}
    while len(seeds) < SEED_COUNT:
        progressed = False
        for key in order:
            rows_in_bucket = buckets[key]
            while cursor[key] < len(rows_in_bucket):
                row = rows_in_bucket[cursor[key]]
                cursor[key] += 1
                name = row.get("file_name")
                if per_file.get(name, 0) >= SEED_PER_DOC_CAP:
                    continue
                if row.get("chunk_id") in seen:
                    continue
                seen.add(row.get("chunk_id"))
                per_file[name] = per_file.get(name, 0) + 1
                seeds.append(row)
                progressed = True
                break
            if len(seeds) >= SEED_COUNT:
                break
        if not progressed:
            break
    return seeds


def keyword_friendly(query):
    return bool(ASCII_IDENTIFIER.search(query or ""))


def build_cases(agent, seeds, generator):
    cases = []
    failures = []
    counter = 0
    for index, seed in enumerate(seeds, 1):
        try:
            result = agent.gen_queries(
                seed.get("file_name"), seed.get("section_path"), seed.get("chunk_text")
            )
        except Exception as exc:
            failures.append((seed.get("chunk_id"), repr(exc)))
            print(f"[{index}/{len(seeds)}] chunk={seed.get('chunk_id')} 生成失败：{exc}")
            continue

        queries = result.get("queries") or []
        for item in queries:
            counter += 1
            query = (item.get("query") or "").strip()
            query_type = (item.get("type") or "unknown").strip()
            cases.append(
                {
                    "case_id": f"rag_zh_{counter:03d}",
                    "category": query_type,
                    "difficulty": DIFFICULTY_BY_TYPE.get(query_type, "MEDIUM"),
                    "query": query,
                    "qrels": [{"chunk_id": seed.get("chunk_id"), "relevance": 1}],
                    "seed_chunk_id": seed.get("chunk_id"),
                    "source_file": seed.get("file_name"),
                    "section_path": seed.get("section_path"),
                    "section_depth": seed.get("section_depth"),
                    "keyword_friendly": keyword_friendly(query),
                    "generator": generator,
                    "reviewed": False,
                    "notes": "",
                }
            )
        print(f"[{index}/{len(seeds)}] chunk={seed.get('chunk_id')} 候选 {len(queries)} 条")
    return cases, failures


def main():
    config.require_credentials()
    rows = load_snapshot(config.KB_SNAPSHOT)
    print(f"[snapshot] 读入 {len(rows)} 个切片")

    seeds = sample_seeds(rows)
    print(f"[seeds] 抽中 {len(seeds)} 个种子切片，覆盖 {len({s.get('file_name') for s in seeds})} 篇文档")

    agent = api.AgentClient()
    agent.login()
    version = agent.gen_query_version().get("promptVersion")

    cases, failures = build_cases(agent, seeds, f"judgeChatModel/{version}")

    with open(DRAFT_PATH, "w", encoding="utf-8") as handle:
        for case in cases:
            handle.write(json.dumps(case, ensure_ascii=False) + "\n")

    print(f"[draft] {DRAFT_PATH} 共 {len(cases)} 条候选，待人工复核")
    if failures:
        print(f"[warn] {len(failures)} 个种子生成失败，需重跑")


if __name__ == "__main__":
    main()
