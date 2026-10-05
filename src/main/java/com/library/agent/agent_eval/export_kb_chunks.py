import json
import os
import sys

import client as api
import config

SECTION_SEPARATOR = " > "

DOC_LIMIT = int(os.environ.get("AGENT_EVAL_KB_DOC_LIMIT", "0"))

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def iter_documents(agent, status):
    page = 1
    while True:
        result = agent.list_documents(status=status, page=page, size=config.KB_DOC_PAGE_SIZE)
        items = result.get("items") or []
        for item in items:
            yield item
        total = result.get("total") or 0
        if page * config.KB_DOC_PAGE_SIZE >= total or not items:
            return
        page += 1


def iter_chunks(agent, file_id):
    page = 1
    while True:
        result = agent.list_chunks(file_id, page=page, size=config.KB_CHUNK_PAGE_SIZE)
        items = result.get("items") or []
        for item in items:
            yield item
        total = result.get("total") or 0
        if page * config.KB_CHUNK_PAGE_SIZE >= total or not items:
            return
        page += 1


def section_depth(section_path):
    if not section_path:
        return 0
    return len(section_path.split(SECTION_SEPARATOR))


def to_row(chunk):
    path = chunk.get("sectionPath")
    return {
        "chunk_id": chunk.get("chunkId"),
        "file_id": chunk.get("fileId"),
        "file_name": chunk.get("fileName"),
        "chunk_index": chunk.get("chunkIndex"),
        "chunk_text": chunk.get("chunkText"),
        "chunk_length": chunk.get("chunkLength"),
        "section_path": path,
        "source_url": chunk.get("sourceUrl"),
        "section_depth": section_depth(path),
    }


def main():
    config.require_credentials()
    agent = api.AgentClient()
    agent.login()

    documents = []
    for document in iter_documents(agent, "EMBEDDED"):
        documents.append(document)
        if DOC_LIMIT > 0 and len(documents) >= DOC_LIMIT:
            break
    print(f"[docs] 待导出文档 {len(documents)} 篇")

    rows = []
    mismatch = []
    for index, document in enumerate(documents, 1):
        file_id = document.get("id")
        file_name = document.get("fileName")
        chunks = list(iter_chunks(agent, file_id))
        declared = document.get("chunkCount")
        if declared is not None and declared != len(chunks):
            mismatch.append((file_id, file_name, declared, len(chunks)))
        for chunk in chunks:
            rows.append(to_row(chunk))
        print(f"[{index}/{len(documents)}] {file_name} chunks={len(chunks)}")

    with open(config.KB_SNAPSHOT, "w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")

    print(f"[snapshot] {config.KB_SNAPSHOT} 共 {len(rows)} 行")
    if mismatch:
        print("[warn] 以下文档的 chunkCount 与实际切片数不一致：")
        for file_id, file_name, declared, actual in mismatch:
            print(f"    file_id={file_id} {file_name} 声明={declared} 实际={actual}")


if __name__ == "__main__":
    main()
