import math
import os
from statistics import mean

import config

K_PATHS = (1, 3, 5, 10, 20, 50, 80, 100)
K_RERANK = (1, 3, 5, 10, 20, 50)

FUSION_K = 80
BREAKAGE_K = 10
LAYER_K = 10

LAYER_KEYS = ("keyword_friendly", "section_depth", "difficulty", "source_file")

MISS_PREVIEW = 10


def fmt(value, digits=3):
    if value is None:
        return "-"
    if isinstance(value, (int, float)):
        return f"{value:.{digits}f}"
    return str(value)


def table(headers, rows):
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join(["---"] * len(headers)) + " |",
    ]
    for row in rows:
        lines.append("| " + " | ".join(str(cell) for cell in row) + " |")
    return "\n".join(lines)


def relevant_set(qrels):
    ids = set()
    for item in qrels or []:
        if isinstance(item, dict):
            ids.add(item.get("chunk_id"))
        else:
            ids.add(item)
    return {value for value in ids if value is not None}


def recall_at(ids, relevant, k):
    if not relevant:
        return None
    return sum(1 for value in ids[:k] if value in relevant) / len(relevant)


def first_hit_rank(ids, relevant):
    for index, value in enumerate(ids, 1):
        if value in relevant:
            return index
    return None


def mrr(ids_list, relevants):
    scores = []
    for ids, relevant in zip(ids_list, relevants):
        rank = first_hit_rank(ids, relevant)
        scores.append(1.0 / rank if rank else 0.0)
    return safe_mean(scores)


def mean_rank(ids_list, relevants):
    ranks = [first_hit_rank(ids, relevant) for ids, relevant in zip(ids_list, relevants)]
    hits = [rank for rank in ranks if rank]
    return safe_mean(hits)


def top_k_rate(ids_list, relevants, k):
    if not relevants:
        return None
    hits = 0
    for ids, relevant in zip(ids_list, relevants):
        if any(value in relevant for value in ids[:k]):
            hits += 1
    return hits / len(relevants)


def ndcg_at(ids, relevant, k):
    if not relevant:
        return None
    dcg = 0.0
    for index, value in enumerate(ids[:k], 1):
        if value in relevant:
            dcg += 1.0 / math.log2(index + 1)
    ideal = min(len(relevant), k)
    idcg = sum(1.0 / math.log2(index + 1) for index in range(1, ideal + 1))
    return dcg / idcg if idcg else None


def safe_mean(values):
    values = [value for value in values if value is not None]
    return mean(values) if values else None


def union_ids(vector_ids, keyword_ids):
    best = {}
    for rank, value in enumerate(vector_ids, 1):
        best.setdefault(value, rank)
    for rank, value in enumerate(keyword_ids, 1):
        if value not in best or rank < best[value]:
            best[value] = rank
    return [value for value, _ in sorted(best.items(), key=lambda item: item[1])]


def path_of(detail, key):
    stage = detail.get("stage") or {}
    if key == "union":
        return union_ids(stage.get("vectorIds") or [], stage.get("keywordIds") or [])
    if key == "reranked":
        reranked = stage.get("rerankedIds")
        return reranked if reranked is not None else (stage.get("mergedIds") or [])
    if key == "final":
        return stage.get("finalIds") or []
    return stage.get(key + "Ids") or []


def recall_curve(details, key, ks):
    row = {}
    for k in ks:
        values = [
            recall_at(path_of(detail, key), relevant_set(detail.get("qrels")), k)
            for detail in details
        ]
        row[k] = safe_mean(values)
    return row


def header(key, ks):
    return [key] + [f"Recall@{k}" for k in ks]


def curve_row(details, key, ks):
    curve = recall_curve(details, key, ks)
    return [key] + [fmt(curve[k]) for k in ks]


def rank_metrics(details, key, ks):
    ids_list = [path_of(detail, key) for detail in details]
    relevants = [relevant_set(detail.get("qrels")) for detail in details]
    return {
        "mrr": mrr(ids_list, relevants),
        "meanRank": mean_rank(ids_list, relevants),
        "top1": top_k_rate(ids_list, relevants, 1),
        "top3": top_k_rate(ids_list, relevants, 3),
        "ndcg": {k: safe_mean([ndcg_at(ids, rel, k) for ids, rel in zip(ids_list, relevants)]) for k in ks},
    }


def route_breakdown(details):
    counts = {"vector_only": 0, "keyword_only": 0, "both": 0, "neither": 0}
    for detail in details:
        relevant = relevant_set(detail.get("qrels"))
        vector_hit = any(value in relevant for value in path_of(detail, "vector"))
        keyword_hit = any(value in relevant for value in path_of(detail, "keyword"))
        if vector_hit and keyword_hit:
            counts["both"] += 1
        elif vector_hit:
            counts["vector_only"] += 1
        elif keyword_hit:
            counts["keyword_only"] += 1
        else:
            counts["neither"] += 1
    return counts


def fusion_loss(details):
    lost = []
    deltas = []
    for detail in details:
        relevant = relevant_set(detail.get("qrels"))
        union = path_of(detail, "union")
        merged = path_of(detail, "merged")
        union_recall = recall_at(union, relevant, FUSION_K)
        merged_recall = recall_at(merged, relevant, FUSION_K)
        if union_recall is None or merged_recall is None:
            continue
        deltas.append(union_recall - merged_recall)
        if union_recall > merged_recall:
            lost.append(detail)
    return safe_mean(deltas), lost


def rerank_effect(details):
    broken = []
    before = []
    after = []
    for detail in details:
        relevant = relevant_set(detail.get("qrels"))
        merged = path_of(detail, "merged")
        reranked = path_of(detail, "reranked")
        before.append(1.0 / first_hit_rank(merged, relevant) if first_hit_rank(merged, relevant) else 0.0)
        after.append(1.0 / first_hit_rank(reranked, relevant) if first_hit_rank(reranked, relevant) else 0.0)
        merged_hit = any(value in relevant for value in merged[:BREAKAGE_K])
        reranked_hit = any(value in relevant for value in reranked[:BREAKAGE_K])
        if merged_hit and not reranked_hit:
            broken.append(detail)
    return {
        "mrrBefore": safe_mean(before),
        "mrrAfter": safe_mean(after),
        "improvement": (safe_mean(after) or 0.0) - (safe_mean(before) or 0.0),
        "broken": broken,
    }


def layered_stats(details, key):
    groups = {}
    for detail in details:
        value = detail.get(key)
        bucket = groups.setdefault(value, {"total": 0, "recall": [], "ndcg": []})
        relevant = relevant_set(detail.get("qrels"))
        ids = path_of(detail, "reranked")
        bucket["total"] += 1
        bucket["recall"].append(recall_at(ids, relevant, LAYER_K))
        bucket["ndcg"].append(ndcg_at(ids, relevant, LAYER_K))
    rows = []
    for value in sorted(groups, key=lambda item: str(item)):
        bucket = groups[value]
        rows.append([value, bucket["total"], fmt(safe_mean(bucket["recall"])), fmt(safe_mean(bucket["ndcg"]))])
    return rows


def missed_cases(details):
    missed = []
    for detail in details:
        relevant = relevant_set(detail.get("qrels"))
        reranked = path_of(detail, "reranked")
        if not any(value in relevant for value in reranked[:LAYER_K]):
            missed.append(detail)
    return missed


def build_report(run_id, view, details):
    run = view.get("run") or {}
    total = len(details)

    parts = [f"# RAG 检索测评报告 `{run_id}`\n"]

    parts.append("## 一、批次元信息\n")
    parts.append(
        table(
            ["项", "值"],
            [
                ["套件", run.get("suiteName")],
                ["测试集版本", run.get("datasetVersion")],
                ["Agent Commit", run.get("agentCommit")],
                ["出题 Prompt 版本", run.get("judgePromptVersion")],
                ["开始时间", run.get("startedAt")],
                ["结束时间", run.get("finishedAt")],
            ],
        )
    )

    reranked_curve = recall_curve(details, "reranked", K_RERANK)
    merged_curve = recall_curve(details, "merged", K_RERANK)
    reranked_rank = rank_metrics(details, "reranked", K_RERANK)
    duration = safe_mean([detail.get("durationMs") for detail in details])

    parts.append("\n## 二、总览\n")
    parts.append(
        table(
            ["指标", "值"],
            [
                ["用例总数", total],
                [f"整体召回率 Recall@{LAYER_K}（rerank 后）", fmt(reranked_curve.get(LAYER_K))],
                [f"整体召回率 Recall@{LAYER_K}（融合后、重排前）", fmt(merged_curve.get(LAYER_K))],
                ["MRR（rerank 后）", fmt(reranked_rank["mrr"])],
                ["Mean Rank（rerank 后）", fmt(reranked_rank["meanRank"])],
                ["Top1 命中率", fmt(reranked_rank["top1"])],
                ["Top3 命中率", fmt(reranked_rank["top3"])],
                [f"nDCG@{LAYER_K}", fmt(reranked_rank["ndcg"].get(LAYER_K))],
                ["平均单次检索耗时(ms)", fmt(duration, 1)],
            ],
        )
    )

    parts.append(f"\n### Recall@k 对照（k = {', '.join(str(k) for k in K_RERANK)}）\n")
    parts.append(
        table(
            ["路径"] + [f"@{k}" for k in K_RERANK],
            [
                ["rerank 后（最终输出）"] + [fmt(reranked_curve[k]) for k in K_RERANK],
                ["融合后、重排前"] + [fmt(merged_curve[k]) for k in K_RERANK],
            ],
        )
    )

    parts.append("\n## 三、双路召回对比\n")
    parts.append(
        table(
            ["路径"] + [f"@{k}" for k in K_PATHS],
            [curve_row(details, key, K_PATHS) for key in ("vector", "keyword", "union", "merged", "reranked")],
        )
    )
    breakdown = route_breakdown(details)
    parts.append("\n### 命中来源分布\n")
    parts.append(
        table(
            ["命中来源", "用例数", "占比"],
            [
                [label, breakdown[key], fmt(breakdown[key] / total if total else None)]
                for key, label in (
                    ("both", "双路共同命中"),
                    ("vector_only", "仅向量路命中"),
                    ("keyword_only", "仅关键词路命中"),
                    ("neither", "双路全漏"),
                )
            ],
        )
    )

    parts.append("\n## 四、RRF 融合损失\n")
    loss, lost = fusion_loss(details)
    parts.append(
        table(
            ["口径", "值"],
            [
                [f"并集上限 Recall@{FUSION_K}", fmt(recall_curve(details, "union", (FUSION_K,))[FUSION_K])],
                [f"RRF 融合 Recall@{FUSION_K}", fmt(recall_curve(details, "merged", (FUSION_K,))[FUSION_K])],
                [f"融合损失（并集 - 融合）", fmt(loss)],
                ["融合掉出用例数", len(lost)],
            ],
        )
    )
    if lost:
        parts.append("\n### 融合掉出明细\n")
        parts.append(
            table(
                ["用例", "Query", "qrels", "并集前 %d" % FUSION_K],
                [
                    [
                        detail.get("caseId"),
                        detail.get("query"),
                        ", ".join(str(v) for v in sorted(relevant_set(detail.get("qrels")))),
                        ", ".join(str(v) for v in path_of(detail, "union")[:MISS_PREVIEW]),
                    ]
                    for detail in lost
                ],
            )
        )

    parts.append("\n## 五、rerank 效果\n")
    effect = rerank_effect(details)
    parts.append(
        table(
            ["口径", "MRR", "Mean Rank", "Top1", "Top3", f"nDCG@{LAYER_K}"],
            [
                [
                    "重排前（RRF 融合顺序）",
                    fmt(rank_metrics(details, "merged", K_RERANK)["mrr"]),
                    fmt(rank_metrics(details, "merged", K_RERANK)["meanRank"]),
                    fmt(rank_metrics(details, "merged", K_RERANK)["top1"]),
                    fmt(rank_metrics(details, "merged", K_RERANK)["top3"]),
                    fmt(rank_metrics(details, "merged", K_RERANK)["ndcg"].get(LAYER_K)),
                ],
                [
                    "重排后（最终输出）",
                    fmt(effect["mrrAfter"]),
                    fmt(reranked_rank["meanRank"]),
                    fmt(reranked_rank["top1"]),
                    fmt(reranked_rank["top3"]),
                    fmt(reranked_rank["ndcg"].get(LAYER_K)),
                ],
            ],
        )
    )
    parts.append(
        table(
            ["口径", "值"],
            [
                ["MRR 提升", fmt(effect["improvement"])],
                [f"破坏用例数（重排前 @{BREAKAGE_K} 命中、重排后 @{BREAKAGE_K} 掉出）", len(effect["broken"])],
            ],
        )
    )
    if effect["broken"]:
        parts.append("\n### 被 rerank 破坏的用例\n")
        parts.append(
            table(
                ["用例", "Query", "重排前 @%d" % BREAKAGE_K, "重排后 @%d" % BREAKAGE_K],
                [
                    [
                        detail.get("caseId"),
                        detail.get("query"),
                        ", ".join(str(v) for v in path_of(detail, "merged")[:MISS_PREVIEW]),
                        ", ".join(str(v) for v in path_of(detail, "reranked")[:MISS_PREVIEW]),
                    ]
                    for detail in effect["broken"]
                ],
            )
        )

    parts.append("\n## 六、分层（rerank 后）\n")
    for key in LAYER_KEYS:
        parts.append(f"\n### 按 {key}\n")
        parts.append(table([key, "用例数", f"Recall@{LAYER_K}", f"nDCG@{LAYER_K}"], layered_stats(details, key)))

    parts.append("\n## 七、漏召用例明细\n")
    missed = missed_cases(details)
    if not missed:
        parts.append(f"无漏召用例（Recall@{LAYER_K} 全部 > 0）。")
    else:
        parts.append(
            table(
                ["用例", "类型", "Query", "qrels", "重排后前 %d" % MISS_PREVIEW],
                [
                    [
                        detail.get("caseId"),
                        detail.get("category"),
                        detail.get("query"),
                        ", ".join(str(v) for v in sorted(relevant_set(detail.get("qrels")))),
                        ", ".join(str(v) for v in path_of(detail, "reranked")[:MISS_PREVIEW]),
                    ]
                    for detail in missed
                ],
            )
        )

    parts.append("\n## 八、口径与差异说明\n")
    parts.append(
        "\n".join(
            [
                "- **本报告是「检索模块能力口径」**：驱动的是 `KbRetrievalServiceImpl.retrieve`"
                "（`minScore=0.0`、候选 80、向量路与关键词路各 top100），"
                "**不是**线上回答链路的 `RagServiceImpl.buildRagPrompt`"
                "（后者另有 query 改写、无距离阈值的 `selectTopKChunkIds`、硬编码 `rerank(...,5,0.7)`）。"
                "两者口径不同，不可直接互相换算。",
                "- 每次请求固定 `topK=50`、`rerank=true`、不限文件，因此 rerank 输出深度上限为 50"
                "（`clamp(topK,1,50)`），rerank 类指标的 k 上限取 50，重排无法覆盖全部 80 个候选。",
                "- RRF 融合损失的「并集」按各切片在两路中最靠前的排名排序（同 rank 时向量路优先），"
                "因此该口径是**并集上限**，不代表某一具体融合算法。",
                "- 关键词路使用 standard 分词器，对纯中文 query 天然偏低；"
                "`keyword_friendly`（query 是否含 ASCII 标识符）分层用于解释这一差异，属结论而非缺陷。",
                "- 漏召与破坏明细中的 chunk id 可在 `datasets/kb_chunks_snapshot.jsonl` 里按 `chunk_id` 反查原文。",
            ]
        )
    )

    parts.append("")
    return "\n".join(parts)


def write_report(run_id, view, details):
    os.makedirs(config.RAG_REPORT_DIR, exist_ok=True)
    name = run_id if run_id.startswith("rag_") else f"rag_{run_id}"
    path = os.path.join(config.RAG_REPORT_DIR, f"{name}.md")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(build_report(run_id, view, details))
    return path
