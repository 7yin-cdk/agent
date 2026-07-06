import json
import requests
from pathlib import Path
from collections import defaultdict, OrderedDict
from beir.retrieval.evaluation import EvaluateRetrieval

BASE = Path("exported/scifact")
API = "http://localhost:8084/eval/beir/scifact/search-docs"

TOKEN = "vTP92D7HgzD8t3THoJwB2Qo_WwtgQcezlbZxD6boYW0"

headers = {
    "Authorization": f"Bearer {TOKEN}",
    "Content-Type": "application/json",
}

queries = {}
with open(BASE / "queries.jsonl", encoding="utf-8") as f:
    for line in f:
        row = json.loads(line)
        queries[row["query_id"]] = row["query"]

qrels = defaultdict(dict)
with open(BASE / "qrels.jsonl", encoding="utf-8") as f:
    for line in f:
        row = json.loads(line)
        qrels[row["query_id"]][str(row["doc_id"])] = int(row["score"])

k_values = [1, 3, 5, 10, 20, 50, 100]
results = {}

for i, (qid, query) in enumerate(queries.items(), 1):
    resp = requests.post(
        API,
        headers=headers,
        json={
            "query": query,
            "topK": 100,
            "vectorTopK": 300,
            "keywordTopK": 300,
            "candidateTopK": 300,
            "useRerank": False
        },
        timeout=120,
    )
    resp.raise_for_status()

    hits = resp.json()["hits"]

    ranked = OrderedDict()
    for rank, hit in enumerate(hits, 1):
        doc_id = str(hit["beirDocId"])
        if doc_id not in ranked:
            ranked[doc_id] = 1.0 / rank

    results[qid] = ranked

    if i % 20 == 0:
        print(f"searched {i}/{len(queries)}")

evaluator = EvaluateRetrieval(k_values=k_values)
ndcg, _map, recall, precision = evaluator.evaluate(dict(qrels), results, k_values)

print("\nRecall:")
for k, v in recall.items():
    print(f"{k}: {v:.4f}")

print("\nNDCG:")
for k, v in ndcg.items():
    print(f"{k}: {v:.4f}")

print("\nPrecision:")
for k, v in precision.items():
    print(f"{k}: {v:.4f}")

print("\nMAP:")
for k, v in _map.items():
    print(f"{k}: {v:.4f}")