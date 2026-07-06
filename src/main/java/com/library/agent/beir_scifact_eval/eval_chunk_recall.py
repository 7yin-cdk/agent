import json
from collections import OrderedDict, defaultdict
from pathlib import Path

import requests
from beir.retrieval.evaluation import EvaluateRetrieval


BASE = Path("exported/scifact")
API = "http://localhost:8084/eval/beir/scifact/search-chunks"

TOKEN = "vTP92D7HgzD8t3THoJwB2Qo_WwtgQcezlbZxD6boYW0"

HEADERS = {
    "Authorization": f"Bearer {TOKEN}",
    "Content-Type": "application/json",
}

K_VALUES = [1, 3, 5, 10, 50, 100]


def load_chunk_qrels():
    queries = OrderedDict()
    qrels = defaultdict(dict)

    with open(BASE / "chunk_test_set.jsonl", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue

            row = json.loads(line)
            query_id = str(row["query_id"])
            chunk_id = str(row["chunk_id"])

            queries.setdefault(query_id, row["query"])
            qrels[query_id][chunk_id] = int(row.get("score", 1))

    return queries, qrels


def search_chunks(query):
    response = requests.post(
        API,
        headers=HEADERS,
        json={
            "query": query,
            "topK": 100,
            "vectorTopK": 300,
            "keywordTopK": 300,
            "candidateTopK": 300,
            "useRerank": False,
        },
        timeout=120,
    )
    response.raise_for_status()
    return response.json()["hits"]


def main():
    queries, qrels = load_chunk_qrels()
    results = {}

    print(f"chunk qrel queries: {len(queries)}")
    print(f"chunk qrel rows: {sum(len(chunks) for chunks in qrels.values())}")

    for index, (query_id, query) in enumerate(queries.items(), start=1):
        hits = search_chunks(query)

        ranked = OrderedDict()
        for rank, hit in enumerate(hits, start=1):
            chunk_id = str(hit["chunkId"])
            if chunk_id not in ranked:
                ranked[chunk_id] = 1.0 / rank

        results[query_id] = ranked

        if index % 20 == 0:
            print(f"searched {index}/{len(queries)}")

    evaluator = EvaluateRetrieval(k_values=K_VALUES)
    ndcg, _map, recall, precision = evaluator.evaluate(dict(qrels), results, K_VALUES)

    print("\nRecall:")
    for key, value in recall.items():
        print(f"{key}: {value:.4f}")

    print("\nNDCG:")
    for key, value in ndcg.items():
        print(f"{key}: {value:.4f}")

    print("\nPrecision:")
    for key, value in precision.items():
        print(f"{key}: {value:.4f}")

    print("\nMAP:")
    for key, value in _map.items():
        print(f"{key}: {value:.4f}")


if __name__ == "__main__":
    main()
