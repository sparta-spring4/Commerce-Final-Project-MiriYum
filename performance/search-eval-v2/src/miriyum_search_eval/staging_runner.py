from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from hashlib import sha256
import json
import math
import os
from pathlib import Path
import re
import time
from typing import Any, Callable, Protocol
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


CALL_SCHEMA_VERSION = "miriyum-staging-search-call-v2"
PUBLIC_ID = re.compile(r"^[1-9][0-9]*$")
SHA40 = re.compile(r"^[0-9a-f]{40}$")
SHA64 = re.compile(r"^[0-9a-f]{64}$")
RUN_ID = re.compile(r"^[A-Za-z0-9._-]{1,100}$")
ALLOWED_BASE_URL = "https://staging-api.miriyum.click"


@dataclass(frozen=True)
class StagingEvalConfig:
    base_url: str
    run_id: str
    backend_sha: str
    harness_sha: str
    dataset_sha: str
    requested_model: str
    temperature: float
    system_instruction_sha: str
    json_schema_sha: str
    staging_approved: bool = False
    harness_source_verified: bool = False
    cloudwatch_evidence_verified: bool = False
    request_interval_seconds: float = 1.5
    timeout_seconds: float = 10.0
    max_retries: int = 4
    backoff_base_seconds: float = 1.0
    max_calls: int = 10_000
    max_http_attempts: int = 12_000
    circuit_breaker_failures: int = 3
    approved_window_start_epoch: float = 0.0
    approved_window_end_epoch: float = float("inf")

    def validate(self) -> None:
        if self.base_url.rstrip("/") != ALLOWED_BASE_URL:
            raise RuntimeError("approved staging base URL is required")
        if not RUN_ID.fullmatch(self.run_id):
            raise RuntimeError("staging run ID is invalid")
        if not SHA40.fullmatch(self.backend_sha) or not SHA40.fullmatch(self.harness_sha):
            raise RuntimeError("backend and harness full SHA are required")
        if not SHA64.fullmatch(self.dataset_sha):
            raise RuntimeError("dataset SHA-256 is required")
        if not self.requested_model.strip():
            raise RuntimeError("requested deployment model is required")
        if not isinstance(self.temperature, (int, float)) or not math.isfinite(self.temperature):
            raise RuntimeError("deployment temperature is required")
        if not SHA64.fullmatch(self.system_instruction_sha) or not SHA64.fullmatch(self.json_schema_sha):
            raise RuntimeError("system instruction and JSON schema SHA-256 are required")
        if not self.staging_approved:
            raise RuntimeError("staging execution approval is required")
        if not self.harness_source_verified:
            raise RuntimeError("clean harness source verification is required")
        if not self.cloudwatch_evidence_verified:
            raise RuntimeError("CloudWatch LLM evidence is required")
        if self.request_interval_seconds < 0 or self.timeout_seconds <= 0:
            raise RuntimeError("staging timing configuration is invalid")
        if self.max_retries < 0 or self.max_calls < 1 or self.max_http_attempts < self.max_calls:
            raise RuntimeError("staging safety caps are invalid")
        if self.approved_window_end_epoch <= self.approved_window_start_epoch:
            raise RuntimeError("approved staging execution window is invalid")


@dataclass(frozen=True)
class StagingTransportResponse:
    status: int
    body: dict[str, Any] | None
    latency_ms: float
    retry_after_seconds: float | None = None


class StagingTransport(Protocol):
    def search(
        self, *, base_url: str, query_text: str, size: int, timeout_seconds: float,
    ) -> StagingTransportResponse: ...


class UrllibStagingTransport:
    def search(
        self, *, base_url: str, query_text: str, size: int, timeout_seconds: float,
    ) -> StagingTransportResponse:
        url = f"{base_url.rstrip('/')}/api/v1/stores?{urlencode({'searchInput': query_text, 'size': size})}"
        request = Request(url, method="GET", headers={"Accept": "application/json"})
        started = time.monotonic()
        try:
            with urlopen(request, timeout=timeout_seconds) as response:
                raw = response.read()
                return StagingTransportResponse(
                    status=response.status,
                    body=_json_object(raw),
                    latency_ms=(time.monotonic() - started) * 1000,
                    retry_after_seconds=_retry_after(response.headers.get("Retry-After")),
                )
        except HTTPError as error:
            raw = error.read()
            return StagingTransportResponse(
                status=error.code,
                body=_json_object(raw),
                latency_ms=(time.monotonic() - started) * 1000,
                retry_after_seconds=_retry_after(error.headers.get("Retry-After")),
            )
        except (TimeoutError, URLError) as error:
            raise TimeoutError("staging search transport failed") from error


def _json_object(raw: bytes) -> dict[str, Any] | None:
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    return value if isinstance(value, dict) else None


def _retry_after(value: str | None) -> float | None:
    if value is None:
        return None
    try:
        parsed = float(value)
    except ValueError:
        return None
    return parsed if parsed >= 0 else None


class StagingCheckpoint:
    def __init__(self, path: Path):
        self.path = path

    def records(self) -> list[dict[str, Any]]:
        if not self.path.exists():
            return []
        return [
            json.loads(line) for line in self.path.read_text(encoding="utf-8").splitlines()
            if line
        ]

    def append(self, record: dict[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
            handle.flush()
            os.fsync(handle.fileno())


def staging_request_id(query: dict[str, Any], repeat_index: int, config: StagingEvalConfig) -> str:
    material = {
        "schemaVersion": CALL_SCHEMA_VERSION,
        "runId": config.run_id,
        "backendSha": config.backend_sha,
        "harnessSha": config.harness_sha,
        "datasetSha256": config.dataset_sha,
        "requestedModel": config.requested_model,
        "temperature": config.temperature,
        "systemInstructionSha256": config.system_instruction_sha,
        "jsonSchemaSha256": config.json_schema_sha,
        "queryId": query.get("id"),
        "queryText": query.get("text"),
        "repeatIndex": repeat_index,
        "size": 50,
    }
    encoded = json.dumps(material, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256(encoded).hexdigest()


def _base_record(
    *, request_id: str, attempt: int, query: dict[str, Any], repeat_index: int,
    config: StagingEvalConfig,
) -> dict[str, Any]:
    return {
        "schemaVersion": CALL_SCHEMA_VERSION,
        "requestId": request_id,
        "attemptId": f"{request_id}:{attempt}",
        "attempt": attempt,
        "runId": config.run_id,
        "backendSha": config.backend_sha,
        "harnessSha": config.harness_sha,
        "datasetSha256": config.dataset_sha,
        "requestedModel": config.requested_model,
        "temperature": config.temperature,
        "systemInstructionSha256": config.system_instruction_sha,
        "jsonSchemaSha256": config.json_schema_sha,
        "queryId": query["id"],
        "queryType": query.get("queryType"),
        "repeatIndex": repeat_index,
    }


def _successful_record(base: dict[str, Any], response: StagingTransportResponse) -> dict[str, Any]:
    body = response.body
    if not isinstance(body, dict) or body.get("code") != "SUCCESS" or not isinstance(body.get("message"), str):
        return {**base, "status": "format_error", "failureKind": "invalid_envelope",
                "providerHttpStatus": response.status, "latencyMs": response.latency_ms}
    data = body.get("data")
    if not isinstance(data, dict) or not isinstance(data.get("items"), list):
        return {**base, "status": "format_error", "failureKind": "invalid_data",
                "providerHttpStatus": response.status, "latencyMs": response.latency_ms}
    store_ids = []
    for item in data["items"]:
        if not isinstance(item, dict) or not isinstance(item.get("storeId"), str) or not PUBLIC_ID.fullmatch(item["storeId"]):
            return {**base, "status": "format_error", "failureKind": "invalid_public_id",
                    "providerHttpStatus": response.status, "latencyMs": response.latency_ms}
        store_id = item["storeId"]
        if not 8_900_001 <= int(store_id) <= 8_900_500:
            return {**base, "status": "format_error", "failureKind": "out_of_corpus_store_id",
                    "providerHttpStatus": response.status, "latencyMs": response.latency_ms}
        store_ids.append(store_id)
    if len(store_ids) != len(set(store_ids)):
        return {**base, "status": "format_error", "failureKind": "duplicate_store_id",
                "providerHttpStatus": response.status, "latencyMs": response.latency_ms}
    required = ("normalizedCondition", "warnings", "ruleVersion", "vocabularyVersion", "rankingRuleVersion", "nextCursor")
    if any(name not in data for name in required) or not isinstance(data["warnings"], list):
        return {**base, "status": "format_error", "failureKind": "invalid_search_contract",
                "providerHttpStatus": response.status, "latencyMs": response.latency_ms}
    return {
        **base,
        "status": "success",
        "failureKind": "",
        "providerHttpStatus": response.status,
        "latencyMs": response.latency_ms,
        "rankedStoreIds": store_ids,
        "ruleVersion": data["ruleVersion"],
        "vocabularyVersion": data["vocabularyVersion"],
        "rankingRuleVersion": data["rankingRuleVersion"],
        "warningCount": len(data["warnings"]),
    }


def _terminal_by_request(records: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    terminal: dict[str, dict[str, Any]] = {}
    for record in records:
        if record.get("status") in {
            "success", "format_error", "provider_error_nonretryable", "provider_error_exhausted",
        }:
            terminal[record["requestId"]] = record
    return terminal


def _validate_checkpoint_provenance(
    records: list[dict[str, Any]], config: StagingEvalConfig,
) -> None:
    expected = {
        "schemaVersion": CALL_SCHEMA_VERSION,
        "runId": config.run_id,
        "backendSha": config.backend_sha,
        "harnessSha": config.harness_sha,
        "datasetSha256": config.dataset_sha,
        "requestedModel": config.requested_model,
        "temperature": config.temperature,
        "systemInstructionSha256": config.system_instruction_sha,
        "jsonSchemaSha256": config.json_schema_sha,
    }
    for record in records:
        if any(record.get(name) != value for name, value in expected.items()):
            raise RuntimeError("checkpoint provenance does not match this staging run")


def _validate_attempt_ledger(records: list[dict[str, Any]]) -> None:
    intents = {record.get("attemptId") for record in records if record.get("status") == "attempt_intent"}
    results = {record.get("attemptId") for record in records if record.get("status") != "attempt_intent"}
    unresolved = intents - results
    if unresolved:
        raise RuntimeError("checkpoint has uncertain billing intent; reconcile before resume")
    if results - intents:
        raise RuntimeError("checkpoint result is missing its durable attempt intent")


def validate_staging_checkpoint(
    checkpoint: StagingCheckpoint, config: StagingEvalConfig,
) -> list[dict[str, Any]]:
    config.validate()
    records = checkpoint.records()
    _validate_checkpoint_provenance(records, config)
    _validate_attempt_ledger(records)
    return [record for record in records if record.get("status") != "attempt_intent"]


def run_staging_requests(
    queries: list[dict[str, Any]], *, repeats: tuple[int, ...] | range,
    config: StagingEvalConfig, checkpoint: StagingCheckpoint, transport: StagingTransport,
    sleep_fn: Callable[[float], None] = time.sleep,
    clock_fn: Callable[[], float] = time.monotonic,
    wall_clock_fn: Callable[[], float] = time.time,
) -> list[dict[str, Any]]:
    config.validate()
    planned = len(queries) * len(repeats)
    if planned > config.max_calls:
        raise RuntimeError("staging logical call cap exceeded before network")
    existing = checkpoint.records()
    result_ledger = validate_staging_checkpoint(checkpoint, config)
    attempts_by_request = Counter(record.get("requestId") for record in result_ledger)
    if len(result_ledger) > config.max_http_attempts:
        raise RuntimeError("staging HTTP attempt cap already exceeded")
    terminal = _terminal_by_request(result_ledger)
    results: list[dict[str, Any]] = []
    consecutive_failures = 0
    last_started: float | None = None

    for query in queries:
        for repeat_index in repeats:
            request_id = staging_request_id(query, repeat_index, config)
            if request_id in terminal:
                final = terminal[request_id]
                results.append(final)
                if final["status"] == "success":
                    consecutive_failures = 0
                else:
                    consecutive_failures += 1
                    if consecutive_failures >= config.circuit_breaker_failures:
                        raise RuntimeError("staging circuit breaker remains open from checkpoint")
                continue
            final: dict[str, Any] | None = None
            start_attempt = attempts_by_request[request_id] + 1
            for attempt in range(start_attempt, config.max_retries + 2):
                wall_now = wall_clock_fn()
                if not config.approved_window_start_epoch <= wall_now <= config.approved_window_end_epoch:
                    raise RuntimeError("approved staging execution window is not active")
                if len(result_ledger) >= config.max_http_attempts:
                    raise RuntimeError("staging HTTP attempt cap reached")
                now = clock_fn()
                if last_started is not None:
                    delay = config.request_interval_seconds - (now - last_started)
                    if delay > 0:
                        sleep_fn(delay)
                wall_now = wall_clock_fn()
                if not config.approved_window_start_epoch <= wall_now <= config.approved_window_end_epoch:
                    raise RuntimeError("approved staging execution window expired during pacing")
                last_started = clock_fn()
                base = _base_record(
                    request_id=request_id, attempt=attempt, query=query,
                    repeat_index=repeat_index, config=config,
                )
                checkpoint.append({**base, "status": "attempt_intent"})
                existing.append({**base, "status": "attempt_intent"})
                retry_after: float | None = None
                try:
                    response = transport.search(
                        base_url=config.base_url, query_text=query["text"], size=50,
                        timeout_seconds=config.timeout_seconds,
                    )
                except TimeoutError:
                    record = {**base, "status": "provider_error", "failureKind": "timeout",
                              "providerHttpStatus": None, "latencyMs": config.timeout_seconds * 1000}
                else:
                    retry_after = response.retry_after_seconds
                    if response.status == 200:
                        record = _successful_record(base, response)
                    elif response.status == 429 or 500 <= response.status <= 599:
                        record = {**base, "status": "provider_error", "failureKind": "http_retryable",
                                  "providerHttpStatus": response.status, "latencyMs": response.latency_ms}
                    else:
                        record = {**base, "status": "provider_error_nonretryable", "failureKind": "http_nonretryable",
                                  "providerHttpStatus": response.status, "latencyMs": response.latency_ms}
                if record["status"] == "provider_error" and attempt == config.max_retries + 1:
                    record = {**record, "status": "provider_error_exhausted"}
                checkpoint.append(record)
                existing.append(record)
                result_ledger.append(record)
                final = record
                if record["status"] in {
                    "success", "format_error", "provider_error_nonretryable", "provider_error_exhausted",
                }:
                    break
                if attempt < config.max_retries + 1:
                    delay = retry_after if retry_after is not None else config.backoff_base_seconds * (2 ** (attempt - start_attempt))
                    if delay > 0:
                        sleep_fn(delay)
            if final is None:
                raise RuntimeError("staging request produced no checkpoint record")
            results.append(final)
            if final["status"] == "success":
                consecutive_failures = 0
            else:
                consecutive_failures += 1
                if consecutive_failures >= config.circuit_breaker_failures:
                    raise RuntimeError("staging circuit breaker opened")
    return results
