import json
import sys
import time

import client as api
import config
import rag_metrics
import runner

BATCH_SIZE = 5
HIT_K = rag_metrics.LAYER_K

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def build_request(case):
    return {
        "query": case["query"],
        "topK": config.RAG_TOPK,
        "rerank": config.RAG_RERANK,
        "fileId": None,
    }


def recall_map(detail):
    stage = detail.get("stage") or {}
    relevant = rag_metrics.relevant_set(detail.get("qrels"))
    result = {}
    for key, ks in (
        ("vector", rag_metrics.K_PATHS),
        ("keyword", rag_metrics.K_PATHS),
        ("union", rag_metrics.K_PATHS),
        ("merged", rag_metrics.K_PATHS),
        ("reranked", rag_metrics.K_RERANK),
    ):
        ids = rag_metrics.path_of(detail, key)
        result[key] = {str(k): rag_metrics.recall_at(ids, relevant, k) for k in ks}
    sizes = {
        "vector": len(stage.get("vectorIds") or []),
        "keyword": len(stage.get("keywordIds") or []),
        "merged": len(stage.get("mergedIds") or []),
        "candidate": len(stage.get("candidateIds") or []),
        "reranked": len(stage.get("rerankedIds") or []),
        "final": len(stage.get("finalIds") or []),
    }
    return result, sizes


def build_retrieval_result(detail):
    relevant = rag_metrics.relevant_set(detail.get("qrels"))
    recalls, sizes = recall_map(detail)
    union = rag_metrics.path_of(detail, "union")
    merged = rag_metrics.path_of(detail, "merged")
    reranked = rag_metrics.path_of(detail, "reranked")
    union_recall = rag_metrics.recall_at(union, relevant, rag_metrics.FUSION_K)
    merged_recall = rag_metrics.recall_at(merged, relevant, rag_metrics.FUSION_K)
    mrr_before = 1.0 / rag_metrics.first_hit_rank(merged, relevant) if rag_metrics.first_hit_rank(merged, relevant) else 0.0
    mrr_after = 1.0 / rag_metrics.first_hit_rank(reranked, relevant) if rag_metrics.first_hit_rank(reranked, relevant) else 0.0
    return {
        "qrels": sorted(relevant),
        "vector_rank": rag_metrics.first_hit_rank(rag_metrics.path_of(detail, "vector"), relevant),
        "keyword_rank": rag_metrics.first_hit_rank(rag_metrics.path_of(detail, "keyword"), relevant),
        "merged_rank": rag_metrics.first_hit_rank(merged, relevant),
        "reranked_rank": rag_metrics.first_hit_rank(reranked, relevant),
        "recall_at": recalls,
        "rrf_loss_at_80": (union_recall - merged_recall)
        if union_recall is not None and merged_recall is not None
        else None,
        "rerank_improved": mrr_after > mrr_before,
        "stage_sizes": sizes,
    }


def build_payload(case, detail, success, reason, duration_ms, retrieval_result):
    return {
        "caseId": case["case_id"],
        "category": case.get("category"),
        "difficulty": case.get("difficulty"),
        "query": case["query"],
        "taskSuccess": success,
        "successReason": reason,
        "retrievalResult": retrieval_result,
        "durationMs": duration_ms,
        "rawOutput": None,
        "errorMessage": None,
    }


def script_error(case, exc):
    return {
        "caseId": case["case_id"],
        "category": case.get("category"),
        "difficulty": case.get("difficulty"),
        "query": case["query"],
        "taskSuccess": False,
        "successReason": "评测脚本异常：" + repr(exc),
        "errorMessage": repr(exc),
    }


def detail_from_case(case, response):
    return {
        "caseId": case["case_id"],
        "category": case.get("category"),
        "difficulty": case.get("difficulty"),
        "query": case["query"],
        "source_file": case.get("source_file"),
        "section_depth": case.get("section_depth"),
        "keyword_friendly": case.get("keyword_friendly"),
        "qrels": case.get("qrels") or [],
        "stage": (response or {}).get("stage") or {},
    }


def judge_success(detail):
    relevant = rag_metrics.relevant_set(detail.get("qrels"))
    reranked = rag_metrics.path_of(detail, "reranked")
    rank = rag_metrics.first_hit_rank(reranked[:HIT_K], relevant)
    if rank:
        return True, f"ok（重排后第 {rank} 位命中）"
    if not reranked:
        return False, "检索未返回任何结果"
    return False, f"重排后前 {HIT_K} 位未命中任何相关切片"


def main():
    config.require_credentials()
    cases = runner.load_cases(config.RAG_DATASET)
    if config.LIMIT > 0:
        cases = cases[: config.LIMIT]
    if not cases:
        raise SystemExit("测试集为空：" + config.RAG_DATASET)

    agent = api.AgentClient()
    agent.login()
    prompt_version = agent.gen_query_version().get("promptVersion")
    created = agent.create_run(
        {
            "runId": config.RUN_ID or None,
            "suiteName": config.RAG_SUITE_NAME,
            "datasetVersion": config.RAG_DATASET_VERSION,
            "agentCommit": runner.git_commit(),
            "modelVersion": config.MODEL_VERSION,
            "judgeVersion": None,
            "judgePromptVersion": prompt_version,
        }
    )
    run_id = created.get("runId")
    print(f"[run] {run_id}  用例 {len(cases)} 条  topK={config.RAG_TOPK} rerank={config.RAG_RERANK}")

    pending = []
    details = []
    done = 0
    for start in range(0, len(cases), BATCH_SIZE):
        batch = cases[start : start + BATCH_SIZE]
        begin = time.time()
        try:
            responses = agent.rag_retrieve_batch([build_request(case) for case in batch])
        except Exception as exc:
            for case in batch:
                pending.append(script_error(case, exc))
            print(f"[batch {start + 1}-{start + len(batch)}] 检索失败：{exc}")
            continue
        share_ms = (time.time() - begin) * 1000 / max(len(batch), 1)

        for case, response in zip(batch, responses):
            detail = detail_from_case(case, response)
            success, reason = judge_success(detail)
            detail["durationMs"] = share_ms
            retrieval_result = build_retrieval_result(detail)
            pending.append(
                build_payload(case, detail, success, reason, share_ms, retrieval_result)
            )
            details.append(detail)
            done += 1
            flag = "OK  " if success else "FAIL"
            print(f"[{done}/{len(cases)}] {case['case_id']} {flag} {reason}")

        agent.save_cases(run_id, pending)
        pending = []

    if pending:
        agent.save_cases(run_id, pending)

    view = agent.finish_run(run_id, "FINISHED")
    report_path = rag_metrics.write_report(run_id, view, details)
    print(f"[report] {report_path}")


if __name__ == "__main__":
    main()
