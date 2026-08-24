from __future__ import annotations

from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from dataclasses import asdict, dataclass
from hashlib import sha256
import json
import os
from pathlib import Path
import random
import threading
import time
from typing import Any, Callable, Iterable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


SYSTEM_INSTRUCTION = (
    "사용자 원문에 실제 음식, 재료, 맛 또는 조리법 근거가 있는지 먼저 "
    "판정하세요. 음식 계열을 안전하게 특정할 수 있으면 interpretation은 "
    "MATCHABLE이고 concepts에 짧은 한국어 음식명과 검색 동의어를 최대 "
    "8개까지 가장 관련 높은 순서로 작성하세요. 실제 음식 근거가 없으면 "
    "interpretation은 NO_FOOD_SIGNAL이고 concepts는 빈 배열입니다. 음식 "
    "관련 가능성은 있지만 음식 계열을 안전하게 특정하기 어려우면 "
    "interpretation은 AMBIGUOUS이고 concepts는 빈 배열입니다. 은유나 "
    "음식과 무관한 표현을 메뉴명으로 바꾸거나 입력에 없는 메뉴명을 "
    "발명하지 마세요. MATCHABLE에서는 맛, 재료, 국물 여부, 조리 형태를 "
    "종합하되 서로 다른 음식 계열의 후보를 다양하게 제시하고 같은 "
    "계열의 표현만 반복하지 마세요. "
    "'메뉴명:', '재료:', '맛:', '조리형태:' 같은 라벨이나 설명 문장을 "
    "쓰지 마세요. 알레르기, 식이 안전, 재고, 예약 가능 여부를 "
    "추론하지 마세요."
)
ATTRIBUTE_EVIDENCE_SYSTEM_INSTRUCTION = (
    "사용자 원문에 실제 음식, 재료, 맛 또는 조리법 근거가 있는지 먼저 "
    "판정하세요. 실제 음식 근거가 없으면 interpretation은 NO_FOOD_SIGNAL, "
    "음식 가능성은 있지만 안전하게 특정하기 어려우면 AMBIGUOUS이며 두 "
    "경우 concepts는 빈 배열입니다. MATCHABLE이면 concepts에 짧은 한국어 "
    "검색어를 관련도 순으로 최대 8개 작성하세요. 원문에 명시된 메뉴명이 "
    "있으면 그 메뉴명과 가까운 별칭을 먼저 보존하세요. 메뉴명이 없으면 "
    "원문에 함께 나온 재료, 맛, 국물 여부, 조리법을 모두 만족하는 구체적 "
    "메뉴 후보를 먼저 쓰고, 남는 칸에는 원문에 실제 등장한 핵심 속성어를 "
    "짧게 쓰세요. 후보 다양성보다 원문의 속성 일치를 우선하세요. 원문에 "
    "없는 재료·맛·조리법·메뉴명을 발명하지 마세요. '메뉴명:', '재료:', "
    "'맛:', '조리형태:' 같은 라벨이나 설명 문장은 쓰지 마세요. 알레르기, "
    "식이 안전, 재고, 예약 가능 여부를 추론하지 마세요."
)
JSON_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["interpretation", "concepts"],
    "properties": {
        "interpretation": {
            "type": "string",
            "enum": ["MATCHABLE", "AMBIGUOUS", "NO_FOOD_SIGNAL"],
        },
        "concepts": {
            "type": "array",
            "maxItems": 8,
            "items": {"type": "string", "maxLength": 60},
        }
    },
}


@dataclass(frozen=True)
class EvalConfig:
    model: str = "gpt-4o-mini"
    temperature: float = 0.0
    max_tokens: int = 100
    timeout_seconds: float = 10.0
    concurrency: int = 4
    max_retries: int = 4
    backoff_base_seconds: float = 0.5
    max_calls: int = 10_000
    cost_cap_usd: float = 1.50
    estimated_input_tokens: int = 220
    estimated_output_tokens: int = 35
    input_usd_per_million: float = 0.15
    output_usd_per_million: float = 0.60
    system_instruction_override: str | None = None
    reasoning_effort: str | None = None

    def system_instruction(self) -> str:
        return self.system_instruction_override or SYSTEM_INSTRUCTION

    def fingerprint(self) -> str:
        material = asdict(self)
        material.pop("system_instruction_override")
        reasoning_effort = material.pop("reasoning_effort")
        material["systemInstruction"] = self.system_instruction()
        if reasoning_effort is not None:
            material["reasoningEffort"] = reasoning_effort
        material["jsonSchema"] = JSON_SCHEMA
        return sha256(json.dumps(material, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def build_request(query_text: str, config: EvalConfig) -> dict[str, Any]:
    body: dict[str, Any] = {
        "model": config.model,
        "messages": [
            {"role": "system", "content": config.system_instruction()},
            {"role": "user", "content": query_text},
        ],
        "temperature": config.temperature,
        "response_format": {
            "type": "json_schema",
            "json_schema": {"name": "search_concepts", "strict": True, "schema": JSON_SCHEMA},
        },
    }
    if config.reasoning_effort is None:
        body["max_tokens"] = config.max_tokens
    else:
        body["max_completion_tokens"] = config.max_tokens
        body["reasoning_effort"] = config.reasoning_effort
    return body


def deterministic_request_id(dataset_sha: str, query_id: str, repeat_index: int, config: EvalConfig) -> str:
    value = f"{dataset_sha}\n{query_id}\n{repeat_index}\n{config.fingerprint()}".encode("utf-8")
    return "mse2-" + sha256(value).hexdigest()[:32]


def cost_usd(input_tokens: int, output_tokens: int, config: EvalConfig) -> float:
    return (
        input_tokens * config.input_usd_per_million
        + output_tokens * config.output_usd_per_million
    ) / 1_000_000


class CheckpointStore:
    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._records = self._load()

    def _load(self) -> dict[str, dict[str, Any]]:
        records: dict[str, dict[str, Any]] = {}
        if not self.path.exists():
            return records
        with self.path.open("r", encoding="utf-8") as handle:
            for line_number, line in enumerate(handle, 1):
                if not line.strip():
                    continue
                try:
                    record = json.loads(line)
                except json.JSONDecodeError as error:
                    raise ValueError(f"invalid checkpoint JSONL at line {line_number}") from error
                request_id = record.get("requestId")
                if not isinstance(request_id, str) or request_id in records:
                    raise ValueError(f"invalid or duplicate checkpoint requestId at line {line_number}")
                records[request_id] = record
        return records

    def get(self, request_id: str) -> dict[str, Any] | None:
        return self._records.get(request_id)

    def records(self) -> list[dict[str, Any]]:
        return list(self._records.values())

    def append(self, record: dict[str, Any]) -> None:
        request_id = record["requestId"]
        with self._lock:
            if request_id in self._records:
                return
            serialized = json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            forbidden = ("authorization", "openai_api_key", "bearer sk-")
            if any(value in serialized.lower() for value in forbidden):
                raise ValueError("secret-like material rejected from checkpoint")
            with self.path.open("a", encoding="utf-8", newline="\n") as handle:
                handle.write(serialized + "\n")
                handle.flush()
                os.fsync(handle.fileno())
            self._records[request_id] = record

    def quarantine_unbilled_retryable_failures(self, audit_path: Path) -> dict[str, int]:
        """Audit zero-cost retryable failures and make only those IDs resumable."""
        with self._lock:
            retryable = [
                record for record in self._records.values()
                if record.get("status") == "provider_error"
                and record.get("providerHttpStatus") == 429
                and record.get("failureKind") == "http_retryable"
                and "costUsd" in record
                and isinstance(record.get("costUsd"), (int, float))
                and float(record["costUsd"]) == 0.0
            ]
            if not retryable:
                return {"quarantined": 0, "remaining": len(self._records)}
            audit_path.parent.mkdir(parents=True, exist_ok=True)
            with audit_path.open("a", encoding="utf-8", newline="\n") as audit:
                for record in retryable:
                    audit.write(json.dumps(
                        record, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                    ) + "\n")
                audit.flush()
                os.fsync(audit.fileno())
            retryable_ids = {record["requestId"] for record in retryable}
            remaining = {
                request_id: record for request_id, record in self._records.items()
                if request_id not in retryable_ids
            }
            temporary = self.path.with_name(self.path.name + ".resume.tmp")
            with temporary.open("w", encoding="utf-8", newline="\n") as handle:
                for record in remaining.values():
                    handle.write(json.dumps(
                        record, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                    ) + "\n")
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, self.path)
            self._records = remaining
            return {"quarantined": len(retryable), "remaining": len(remaining)}


class OpenAIChatTransport:
    def __init__(self, api_key: str, base_url: str = "https://api.openai.com"):
        if not api_key:
            raise ValueError("OPENAI_API_KEY is required")
        self._api_key = api_key
        self._url = base_url.rstrip("/") + "/v1/chat/completions"

    def __call__(self, body: dict[str, Any], timeout_seconds: float) -> tuple[int, dict[str, str], dict[str, Any]]:
        request = Request(
            self._url,
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers={"Authorization": f"Bearer {self._api_key}", "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urlopen(request, timeout=timeout_seconds) as response:
                payload = json.loads(response.read().decode("utf-8"))
                return response.status, {key.lower(): value for key, value in response.headers.items()}, payload
        except HTTPError as error:
            try:
                payload = json.loads(error.read().decode("utf-8"))
            except Exception:
                payload = {"error": {"type": "unparseable_http_error"}}
            return error.code, {key.lower(): value for key, value in error.headers.items()}, payload
        except (URLError, TimeoutError) as error:
            raise TimeoutError("OpenAI request timed out or was unreachable") from error


def _parse_success(payload: dict[str, Any]) -> tuple[str, list[str], str, int, int]:
    choices = payload.get("choices")
    usage = payload.get("usage")
    if not isinstance(choices, list) or len(choices) != 1 or not isinstance(usage, dict):
        raise ValueError("missing choices or usage")
    choice = choices[0]
    message = choice.get("message") or {}
    if message.get("refusal"):
        raise PermissionError("provider refusal")
    if choice.get("finish_reason") != "stop" or not isinstance(message.get("content"), str):
        raise ValueError("incomplete or missing content")
    document = json.loads(message["content"])
    if not isinstance(document, dict) or set(document) != {"interpretation", "concepts"}:
        raise ValueError("concept document must match the schema")
    interpretation = document.get("interpretation")
    concepts = document.get("concepts") if isinstance(document, dict) else None
    if interpretation not in {"MATCHABLE", "AMBIGUOUS", "NO_FOOD_SIGNAL"}:
        raise ValueError("interpretation schema mismatch")
    if not isinstance(concepts, list) or len(concepts) > 8 or any(not isinstance(value, str) or len(value) > 60 for value in concepts):
        raise ValueError("concept schema mismatch")
    normalized = list(dict.fromkeys(" ".join(value.split()) for value in concepts if value.strip()))
    application_concepts = normalized if interpretation == "MATCHABLE" else []
    return interpretation, application_concepts, str(payload.get("model", "")), int(usage["prompt_tokens"]), int(usage["completion_tokens"])


def _execute_one(
    *, query: dict[str, Any], repeat_index: int, request_id: str, config: EvalConfig,
    transport: Callable[[dict[str, Any], float], tuple[int, dict[str, str], dict[str, Any]]],
    sleep: Callable[[float], None],
) -> dict[str, Any]:
    started = time.perf_counter()
    attempts = 0
    retry_count = 0
    final_status = 0
    payload: dict[str, Any] = {}
    provider_request_id = ""
    failure_kind = ""
    while attempts <= config.max_retries:
        attempts += 1
        try:
            status, headers, payload = transport(build_request(query["text"], config), config.timeout_seconds)
            final_status = status
            provider_request_id = headers.get("x-request-id", "")
            if status == 200:
                break
            retryable = status == 429 or 500 <= status < 600
            failure_kind = "http_retryable" if retryable else "http_non_retryable"
            if not retryable or attempts > config.max_retries:
                break
            retry_count += 1
            retry_after = headers.get("retry-after")
            delay = float(retry_after) if retry_after and retry_after.replace(".", "", 1).isdigit() else config.backoff_base_seconds * (2 ** (attempts - 1))
            sleep(delay + random.random() * min(0.05, delay))
        except TimeoutError:
            failure_kind = "timeout"
            if attempts > config.max_retries:
                break
            retry_count += 1
            delay = config.backoff_base_seconds * (2 ** (attempts - 1))
            sleep(delay + random.random() * min(0.05, delay))
    record: dict[str, Any] = {
        "schemaVersion": "miriyum-search-call-v2",
        "requestId": request_id,
        "queryId": query["id"],
        "repeatIndex": repeat_index,
        "requestedModel": config.model,
        "returnedModel": "",
        "temperature": config.temperature,
        "requestFingerprint": config.fingerprint(),
        "status": "provider_error",
        "providerHttpStatus": final_status,
        "providerRequestId": provider_request_id,
        "attempts": attempts,
        "retryCount": retry_count,
        "failureKind": failure_kind,
        "interpretation": "",
        "concepts": [],
        "inputTokens": 0,
        "outputTokens": 0,
        "costUsd": 0.0,
        "latencyMs": round((time.perf_counter() - started) * 1000, 3),
    }
    if final_status == 200:
        usage = payload.get("usage") if isinstance(payload, dict) else None
        if isinstance(usage, dict):
            try:
                input_tokens = int(usage["prompt_tokens"])
                output_tokens = int(usage["completion_tokens"])
                record.update({
                    "returnedModel": str(payload.get("model", "")),
                    "inputTokens": input_tokens,
                    "outputTokens": output_tokens,
                    "costUsd": cost_usd(input_tokens, output_tokens, config),
                })
            except (KeyError, TypeError, ValueError):
                pass
        try:
            interpretation, concepts, returned_model, input_tokens, output_tokens = _parse_success(payload)
            record.update({
                "status": "success", "failureKind": "", "interpretation": interpretation,
                "concepts": concepts,
                "returnedModel": returned_model, "inputTokens": input_tokens,
                "outputTokens": output_tokens, "costUsd": cost_usd(input_tokens, output_tokens, config),
            })
        except PermissionError:
            record.update({"status": "refusal", "failureKind": "refusal"})
        except (ValueError, KeyError, TypeError, json.JSONDecodeError):
            record.update({"status": "format_error", "failureKind": "malformed_response"})
    return record


def run_requests(
    queries: list[dict[str, Any]], *, repeats: Iterable[int], dataset_sha: str,
    config: EvalConfig, checkpoint: CheckpointStore,
    transport: Callable[[dict[str, Any], float], tuple[int, dict[str, str], dict[str, Any]]],
    sleep: Callable[[float], None] = time.sleep,
) -> list[dict[str, Any]]:
    desired = [
        (query, repeat_index, deterministic_request_id(dataset_sha, query["id"], repeat_index, config))
        for query in queries for repeat_index in repeats
    ]
    if len(desired) > config.max_calls:
        raise ValueError(f"call cap exceeded: {len(desired)} > {config.max_calls}")
    estimated_cost = cost_usd(
        len(desired) * config.estimated_input_tokens,
        len(desired) * config.estimated_output_tokens,
        config,
    )
    if estimated_cost > config.cost_cap_usd:
        raise ValueError(f"estimated cost cap exceeded: {estimated_cost:.6f} > {config.cost_cap_usd:.6f}")
    existing = {request_id: checkpoint.get(request_id) for _, _, request_id in desired}
    pending = [(query, repeat_index, request_id) for query, repeat_index, request_id in desired if existing[request_id] is None]
    checkpoint_cost = sum(float(record.get("costUsd", 0.0)) for record in checkpoint.records())
    if pending and checkpoint_cost >= config.cost_cap_usd:
        raise RuntimeError(
            f"actual cost cap already reached: {checkpoint_cost:.6f} "
            f">= {config.cost_cap_usd:.6f}"
        )
    if pending:
        pending_iterator = iter(pending)
        executor = ThreadPoolExecutor(max_workers=config.concurrency)
        active = set()
        submitted_count = 0
        completed_count = 0

        def submit_next() -> bool:
            nonlocal submitted_count
            try:
                query, repeat_index, request_id = next(pending_iterator)
            except StopIteration:
                return False
            active.add(executor.submit(
                _execute_one, query=query, repeat_index=repeat_index, request_id=request_id,
                config=config, transport=transport, sleep=sleep,
            ))
            submitted_count += 1
            return True

        stop_reason = ""
        try:
            for _ in range(config.concurrency):
                if not submit_next():
                    break
            while active:
                completed, _ = wait(active, return_when=FIRST_COMPLETED)
                for future in completed:
                    active.remove(future)
                    record = future.result()
                    checkpoint.append(record)
                    completed_count += 1
                    actual_cost = sum(float(record.get("costUsd", 0.0)) for record in checkpoint.records())
                    if (
                        record.get("status") == "provider_error"
                        and record.get("providerHttpStatus") == 429
                        and int(record.get("retryCount", 0)) >= config.max_retries
                    ):
                        stop_reason = "persistent 429 circuit breaker"
                    if actual_cost >= config.cost_cap_usd and completed_count < len(pending):
                        stop_reason = (
                            f"actual cost cap reached: {actual_cost:.6f} "
                            f">= {config.cost_cap_usd:.6f}"
                        )
                    if not stop_reason:
                        submit_next()
            if stop_reason:
                raise RuntimeError(stop_reason)
        finally:
            executor.shutdown(wait=True, cancel_futures=False)
    return [checkpoint.get(request_id) for _, _, request_id in desired]  # type: ignore[list-item]
