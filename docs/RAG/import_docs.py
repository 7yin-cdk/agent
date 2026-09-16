import argparse
import os
import sys
import time
from pathlib import Path

import requests

BASE_URL = os.environ.get("AGENT_BASE_URL", "http://localhost:8084").rstrip("/")
USERNAME = os.environ.get("AGENT_USERNAME", "")
PASSWORD = os.environ.get("AGENT_PASSWORD", "")

MARKDOWN_DIR = Path(__file__).resolve().parent / "markdown"

PENDING_STATUSES = {"UPLOADED", "PARSED"}
SUCCESS_STATUS = "EMBEDDED"
FAILED_STATUS = "FAILED"

TARGET_STEMS = [
    "app-pgbasebackup", "app-postgres", "app-vacuumdb",
    "backup-dump", "backup-file", "checksums", "config-setting",
    "continuous-archiving", "different-replication-solutions", "diskusage",
    "explicit-locking", "functions-admin", "high-availability", "hot-standby",
    "logfile-maintenance", "logical-replication", "logical-replication-conflicts",
    "monitoring-locks", "monitoring-ps", "monitoring-stats",
    "mvcc-serialization-failure-handling", "non-durability", "performance-tips",
    "predefined-roles", "progress-reporting", "routine-reindex", "routine-vacuuming",
    "runtime-config-autovacuum", "runtime-config-client", "runtime-config-compatible",
    "runtime-config-connection", "runtime-config-developer",
    "runtime-config-error-handling", "runtime-config-file-locations",
    "runtime-config-locks", "runtime-config-logging", "runtime-config-preset",
    "runtime-config-query", "runtime-config-replication", "runtime-config-resource",
    "runtime-config-statistics", "runtime-config-vacuum", "runtime-config-wal",
    "sql-analyze", "sql-cluster", "sql-explain", "sql-lock", "sql-reindex", "sql-vacuum",
    "transaction-id", "transaction-iso", "using-explain",
    "view-pg-locks", "view-pg-replication-slots", "view-pg-stats",
    "wal-async-commit", "wal-configuration", "wal-reliability",
    "warm-standby", "warm-standby-failover",
]


class KbClient:
    def __init__(self, base_url=BASE_URL):
        self.base_url = base_url
        self.session = requests.Session()
        self.token = None

    def login(self, username=USERNAME, password=PASSWORD):
        if not username or not password:
            raise SystemExit("缺少登录凭据，请设置环境变量 AGENT_USERNAME / AGENT_PASSWORD")
        response = self.session.post(
            f"{self.base_url}/auth/login",
            json={"username": username, "password": password},
            timeout=30,
        )
        response.raise_for_status()
        self.token = response.json()["token"]

    def headers(self):
        return {"Authorization": f"Bearer {self.token}"}

    def list_documents(self, size=500):
        response = self.session.get(
            f"{self.base_url}/agent/kb/documents",
            headers=self.headers(),
            params={"page": 1, "size": size},
            timeout=60,
        )
        response.raise_for_status()
        return response.json().get("items") or []

    def upload(self, path):
        with path.open("rb") as handle:
            response = self.session.post(
                f"{self.base_url}/agent/kb/documents",
                headers=self.headers(),
                files={"file": (path.name, handle, "text/markdown")},
                timeout=120,
            )
        response.raise_for_status()
        return response.json()

    def delete(self, file_id):
        response = self.session.delete(
            f"{self.base_url}/agent/kb/documents/{file_id}",
            headers=self.headers(),
            timeout=60,
        )
        response.raise_for_status()


def status_by_name(client):
    return {item["fileName"]: item for item in client.list_documents()}


def wait_until_settled(client, names, poll_seconds, timeout_seconds):
    deadline = time.time() + timeout_seconds
    while True:
        current = status_by_name(client)
        pending = [
            name for name in names
            if name not in current or current[name].get("status") in PENDING_STATUSES
        ]
        if not pending:
            return current
        if time.time() > deadline:
            print(f"等待超时，仍处于中间状态的文档：{', '.join(pending)}")
            return current
        print(f"入库中，剩余 {len(pending)} 个待完成")
        time.sleep(poll_seconds)


def prepare(client, paths, force):
    existing = status_by_name(client)
    todo = []
    skipped = []

    for path in paths:
        item = existing.get(path.name)
        if item is None:
            todo.append(path)
            continue
        if item.get("status") == SUCCESS_STATUS and not force:
            skipped.append(path.name)
            continue
        client.delete(item["id"])
        print(f"已删除旧版本 {path.name}（原状态 {item.get('status')}）")
        todo.append(path)

    return todo, skipped


def upload_round(client, todo, poll_seconds, timeout_seconds):
    for path in todo:
        client.upload(path)
    print(f"已提交 {len(todo)} 个文档，等待异步入库")
    return wait_until_settled(client, [path.name for path in todo], poll_seconds, timeout_seconds)


def run(client, paths, args):
    todo, skipped = prepare(client, paths, args.force)
    if skipped:
        print(f"跳过已入库的 {len(skipped)} 个文档（如需重新入库请加 --force）")

    for attempt in range(1, args.retries + 2):
        if not todo:
            break
        print(f"第 {attempt} 轮上传，共 {len(todo)} 个")
        current = upload_round(client, todo, args.poll, args.timeout)
        todo = [
            path for path in todo
            if current.get(path.name, {}).get("status") != SUCCESS_STATUS
        ]
        if todo and attempt <= args.retries:
            print(f"失败 {len(todo)} 个，清理后重试：{', '.join(p.name for p in todo)}")
            for path in todo:
                item = current.get(path.name)
                if item:
                    client.delete(item["id"])
            time.sleep(args.poll)

    return status_by_name(client)


def report(final, paths):
    print("\n===== 入库结果 =====")
    failed = []
    total_chunks = 0
    for path in paths:
        item = final.get(path.name)
        status = item.get("status") if item else "MISSING"
        chunks = item.get("chunkCount") if item else None
        if isinstance(chunks, int):
            total_chunks += chunks
        print(f"{status:<10} chunk={chunks!s:<6} {path.name}")
        if status != SUCCESS_STATUS:
            failed.append(path.name)

    print(f"\n成功 {len(paths) - len(failed)} / {len(paths)}，分块合计 {total_chunks}")
    if failed:
        print(f"失败清单：{', '.join(failed)}")
        return 1
    return 0


def parse_args():
    parser = argparse.ArgumentParser(description="批量导入 PostgreSQL 运维文档到知识库")
    parser.add_argument("--only", nargs="*", metavar="STEM",
                        help="只导入指定文件（不含 .md 后缀），用于单文档验证")
    parser.add_argument("--limit", type=int, default=0,
                        help="只导入前 N 个，用于小批量验证")
    parser.add_argument("--force", action="store_true",
                        help="已入库的文档也删除重导")
    parser.add_argument("--retries", type=int, default=2, help="失败重试轮数")
    parser.add_argument("--poll", type=int, default=3, help="状态轮询间隔秒数")
    parser.add_argument("--timeout", type=int, default=900, help="单轮等待超时秒数")
    return parser.parse_args()


def main():
    args = parse_args()
    stems = args.only if args.only else TARGET_STEMS
    if args.limit > 0:
        stems = stems[:args.limit]

    paths = [MARKDOWN_DIR / f"{stem}.md" for stem in stems]
    missing = [path.name for path in paths if not path.exists()]
    if missing:
        raise SystemExit(f"语料缺失：{', '.join(missing)}")

    client = KbClient()
    client.login()
    print(f"已登录 {BASE_URL}，待处理 {len(paths)} 个文档")

    final = run(client, paths, args)
    sys.exit(report(final, paths))


if __name__ == "__main__":
    main()
