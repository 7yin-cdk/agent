import json
from pathlib import Path
from beir import util
from beir.datasets.data_loader import GenericDataLoader

dataset = "scifact"
url = f"https://public.ukp.informatik.tu-darmstadt.de/thakur/BEIR/datasets/{dataset}.zip"

base_dir = Path("datasets")
out_dir = Path("exported") / dataset
out_dir.mkdir(parents=True, exist_ok=True)

data_path = util.download_and_unzip(url, str(base_dir))
corpus, queries, qrels = GenericDataLoader(data_folder=data_path).load(split="test")

with open(out_dir / "corpus.jsonl", "w", encoding="utf-8") as f:
    for doc_id, doc in corpus.items():
        f.write(json.dumps({
            "doc_id": doc_id,
            "title": doc.get("title", ""),
            "text": doc.get("text", ""),
            "content": (doc.get("title", "") + "\n" + doc.get("text", "")).strip()
        }, ensure_ascii=False) + "\n")

with open(out_dir / "queries.jsonl", "w", encoding="utf-8") as f:
    for query_id, query in queries.items():
        f.write(json.dumps({
            "query_id": query_id,
            "query": query
        }, ensure_ascii=False) + "\n")

with open(out_dir / "qrels.jsonl", "w", encoding="utf-8") as f:
    for query_id, docs in qrels.items():
        for doc_id, score in docs.items():
            f.write(json.dumps({
                "query_id": query_id,
                "doc_id": doc_id,
                "score": score
            }, ensure_ascii=False) + "\n")

print("SciFact exported")
print("corpus:", len(corpus))
print("queries:", len(queries))
print("qrels queries:", len(qrels))
print("output:", out_dir.resolve())