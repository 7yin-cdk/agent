import json

import requests

import config


def iter_events(lines):
    event = None
    data_parts = []
    for raw in lines:
        if raw is None:
            continue
        line = raw.decode("utf-8") if isinstance(raw, bytes) else raw
        if line == "":
            if event is not None or data_parts:
                yield event, "\n".join(data_parts)
            event, data_parts = None, []
            continue
        if line.startswith(":"):
            continue
        if line.startswith("event:"):
            event = line[len("event:"):].strip()
        elif line.startswith("data:"):
            data_parts.append(line[len("data:"):].lstrip())
    if event is not None or data_parts:
        yield event, "\n".join(data_parts)


def parse_json(text):
    try:
        return json.loads(text)
    except (TypeError, ValueError):
        return None


class AgentClient:
    def __init__(self, base_url=None):
        self.base_url = (base_url or config.BASE_URL).rstrip("/")
        self.session = requests.Session()
        self.token = None

    def login(self, username=None, password=None):
        response = self.session.post(
            f"{self.base_url}/auth/login",
            json={"username": username or config.USERNAME, "password": password or config.PASSWORD},
            timeout=30,
        )
        response.raise_for_status()
        self.token = response.json()["token"]
        return self.token

    def _auth_headers(self):
        return {"Authorization": f"Bearer {self.token}"}

    def create_conversation(self, title):
        response = self.session.post(
            f"{self.base_url}/agent/conversations",
            headers=self._auth_headers(),
            json={"title": (title or "eval")[:20]},
            timeout=30,
        )
        response.raise_for_status()
        return response.json()["conversationId"]

    def chat_stream(self, query, conversation_id=None):
        payload = {"query": query}
        if conversation_id:
            payload["conversationId"] = conversation_id
        result = {
            "conversationId": None,
            "traceId": None,
            "intentType": None,
            "status": None,
            "answer": "",
            "error": None,
        }
        deltas = []
        with self.session.post(
            f"{self.base_url}/agent/chat/reactive/stream",
            headers=self._auth_headers(),
            json=payload,
            stream=True,
            timeout=config.TIMEOUT_SECONDS,
        ) as response:
            response.raise_for_status()
            for event, data in iter_events(response.iter_lines(decode_unicode=False)):
                obj = parse_json(data)
                if event == "meta" and obj:
                    result["conversationId"] = obj.get("conversationId")
                    result["traceId"] = obj.get("traceId")
                elif event == "status" and obj:
                    result["status"] = obj
                    result["intentType"] = obj.get("intentType")
                elif event == "delta" and obj:
                    deltas.append(obj.get("content") or "")
                elif event == "done" and obj:
                    result["conversationId"] = obj.get("conversationId") or result["conversationId"]
                    result["answer"] = obj.get("answer") or ""
                    break
                elif event == "error" and obj:
                    result["error"] = obj.get("message")
        if not result["answer"]:
            result["answer"] = "".join(deltas)
        return result

    def get_trace(self, trace_id):
        response = self.session.get(
            f"{self.base_url}/agent/observability/traces/{trace_id}",
            headers=self._auth_headers(),
            timeout=30,
        )
        response.raise_for_status()
        return response.json()

    def create_run(self, payload):
        response = self.session.post(
            f"{self.base_url}/eval/runs",
            headers=self._auth_headers(),
            json=payload,
            timeout=30,
        )
        response.raise_for_status()
        return response.json()

    def save_cases(self, run_id, cases):
        response = self.session.post(
            f"{self.base_url}/eval/runs/{run_id}/cases",
            headers=self._auth_headers(),
            json=cases,
            timeout=120,
        )
        response.raise_for_status()
        return response.json().get("inserted", 0)

    def finish_run(self, run_id, status="FINISHED"):
        response = self.session.post(
            f"{self.base_url}/eval/runs/{run_id}/finish",
            headers=self._auth_headers(),
            json={"status": status},
            timeout=60,
        )
        response.raise_for_status()
        return response.json()

    def get_run(self, run_id):
        response = self.session.get(
            f"{self.base_url}/eval/runs/{run_id}",
            headers=self._auth_headers(),
            timeout=60,
        )
        response.raise_for_status()
        return response.json()

    def judge(self, query, answer, key_points):
        response = self.session.post(
            f"{self.base_url}/eval/judge",
            headers=self._auth_headers(),
            json={"query": query, "answer": answer, "keyPoints": key_points or []},
            timeout=config.TIMEOUT_SECONDS,
        )
        response.raise_for_status()
        return response.json()

    def judge_version(self):
        response = self.session.get(
            f"{self.base_url}/eval/judge/version",
            headers=self._auth_headers(),
            timeout=30,
        )
        response.raise_for_status()
        return response.json()
