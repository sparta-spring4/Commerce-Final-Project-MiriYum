from __future__ import annotations

from collections import Counter
from dataclasses import replace
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import subprocess
from typing import Any

from .artifacts import write_json, write_jsonl, write_sha256_manifest
from .staging_catalog import (
    generate_staging_dataset,
    staging_pilot_queries,
    validate_staging_dataset,
)
from .staging_evaluation import aggregate_staging_evaluation, evaluate_staging_records
from .staging_runner import (
    StagingCheckpoint,
    StagingEvalConfig,
    StagingTransport,
    UrllibStagingTransport,
    run_staging_requests,
    staging_request_id,
    validate_staging_checkpoint,
)


STAGING_SEED = 20260825
CLOUDWATCH_PROOF_SCHEMA = "miriyum-staging-cloudwatch-pilot-proof-v1"
FULL_CLOUDWATCH_PROOF_SCHEMA = "miriyum-staging-cloudwatch-full-proof-v1"
PREFLIGHT_SCHEMA = "miriyum-staging-search-preflight-v1"
STAGING_METER_NAMES = {
    "miriyum.search.llm.calls", "miriyum.search.llm.latency",
    "miriyum.search.llm.outcomes", "miriyum.search.llm.tokens",
}
HARNESS_PATHS = (
    "performance/search-eval-v2",
    "performance/k6/search-llm",
    "performance/k6/tests/search-llm-scenario-contract.js",
    "backend/scripts/dev-data/search-profile-demo-500-stores.sql",
    "docs/performance/staging-llm-search-validation.md",
)
REQUIRED_PUBLIC_ID_CONTRACT_BLOB = "08ccb829ac0c7ccedc3c2744ad6f817080b90e45"


def _env_true(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() == "true"


def generate_staging_artifacts(
    root: Path, *, seed_sql: Path, seed: int = STAGING_SEED,
) -> dict[str, Any]:
    dataset = generate_staging_dataset(seed_sql, seed=seed)
    validation = validate_staging_dataset(dataset)
    if validation["errors"]:
        raise RuntimeError(f"staging dataset validation failed: {validation['errors']}")
    root.mkdir(parents=True, exist_ok=True)
    records = [{"recordType": "metadata", **dataset["metadata"]}]
    for key, record_type in (
        ("menuTemplates", "menuTemplate"), ("stores", "store"),
        ("menus", "menu"), ("queries", "query"),
    ):
        records.extend({"recordType": record_type, **value} for value in dataset[key])
    files = [
        write_json(root / "dataset.json", dataset),
        write_jsonl(root / "manifest.jsonl", records),
        write_json(root / "metadata.json", dataset["metadata"]),
        write_jsonl(root / "queries.jsonl", dataset["queries"]),
        write_json(root / "validation.json", validation),
    ]
    write_sha256_manifest(files, root / "dataset-sha256.json")
    return dataset


def load_staging_dataset(root: Path) -> dict[str, Any]:
    path = root / "dataset.json"
    if not path.is_file():
        raise RuntimeError("staging dataset artifact is missing; run staging-generate first")
    dataset = json.loads(path.read_text(encoding="utf-8"))
    validation = validate_staging_dataset(dataset)
    if validation["errors"]:
        raise RuntimeError(f"stored staging dataset is invalid: {validation['errors']}")
    return dataset


def staging_config_from_environment(dataset_sha: str, *, mode: str) -> StagingEvalConfig:
    if mode not in {"pilot", "full"}:
        raise ValueError(f"unsupported staging mode: {mode}")
    pilot = mode == "pilot"
    temperature_text = os.environ.get("STAGING_EVAL_TEMPERATURE", "")
    try:
        temperature = float(temperature_text)
    except ValueError:
        temperature = float("nan")
    return StagingEvalConfig(
        base_url=os.environ.get("STAGING_EVAL_BASE_URL", "https://staging-api.miriyum.click"),
        run_id=os.environ.get("STAGING_EVAL_RUN_ID", ""),
        backend_sha=os.environ.get("STAGING_EVAL_BACKEND_SHA", ""),
        harness_sha=os.environ.get("STAGING_EVAL_HARNESS_SHA", ""),
        dataset_sha=dataset_sha,
        requested_model=os.environ.get("STAGING_EVAL_REQUESTED_MODEL", ""),
        temperature=temperature,
        system_instruction_sha=os.environ.get("STAGING_EVAL_SYSTEM_INSTRUCTION_SHA256", ""),
        json_schema_sha=os.environ.get("STAGING_EVAL_JSON_SCHEMA_SHA256", ""),
        staging_approved=_env_true("STAGING_EVAL_APPROVED"),
        harness_source_verified=_env_true("STAGING_EVAL_HARNESS_VERIFIED"),
        cloudwatch_evidence_verified=_env_true("STAGING_EVAL_CLOUDWATCH_VERIFIED"),
        request_interval_seconds=1.5,
        timeout_seconds=10.0,
        max_retries=4,
        backoff_base_seconds=1.0,
        max_calls=100 if pilot else 10_000,
        max_http_attempts=125 if pilot else 12_000,
        circuit_breaker_failures=3,
    )


def _provenance(config: StagingEvalConfig) -> dict[str, Any]:
    return {
        "runId": config.run_id, "backendSha": config.backend_sha,
        "harnessSha": config.harness_sha, "datasetSha256": config.dataset_sha,
        "requestedModel": config.requested_model,
        "temperature": config.temperature,
        "systemInstructionSha256": config.system_instruction_sha,
        "jsonSchemaSha256": config.json_schema_sha,
    }


def _repository_root() -> Path:
    return Path(__file__).resolve().parents[4]


def verify_harness_checkout(config: StagingEvalConfig) -> dict[str, Any]:
    repository = _repository_root()

    def git(*arguments: str) -> str:
        try:
            completed = subprocess.run(
                ["git", *arguments], cwd=repository, check=True,
                capture_output=True, text=True,
            )
        except (OSError, subprocess.CalledProcessError) as error:
            raise RuntimeError("unable to verify staging harness checkout") from error
        return completed.stdout.strip()

    head = git("rev-parse", "HEAD")
    if head != config.harness_sha:
        raise RuntimeError("staging harness SHA does not match the actual git HEAD")
    dirty = git("status", "--porcelain", "--untracked-files=all", "--", *HARNESS_PATHS)
    if dirty:
        raise RuntimeError("staging harness checkout is dirty")
    public_id_contract_blob = git("rev-parse", "HEAD:performance/k6/search-llm/contracts.js")
    if public_id_contract_blob != REQUIRED_PUBLIC_ID_CONTRACT_BLOB:
        raise RuntimeError("staging harness does not contain the approved PublicId contract")
    return {"headSha": head, "clean": True}


def _pilot_request_ids_sha256(dataset: dict[str, Any], config: StagingEvalConfig) -> str:
    queries = staging_pilot_queries(dataset["queries"], seed=dataset["metadata"]["seed"])
    request_ids = sorted(staging_request_id(query, 0, config) for query in queries)
    return sha256("\n".join(request_ids).encode("ascii")).hexdigest()


def validate_pilot_cloudwatch_proof(
    path: Path, *, expected: dict[str, Any], execution_start: datetime,
    execution_end: datetime, expected_pilot_request_ids_sha256: str,
    validation_now: datetime | None = None,
) -> dict[str, Any]:
    if not path.is_file():
        raise RuntimeError("CloudWatch pilot proof is missing; full staging run is blocked")
    proof = json.loads(path.read_text(encoding="utf-8"))
    if proof.get("schemaVersion") != CLOUDWATCH_PROOF_SCHEMA or proof.get("verified") is not True:
        raise RuntimeError("CloudWatch pilot proof is not verified")
    if (
        proof.get("meterNamespace") != "MiriYum/Staging"
        or set(proof.get("meterNames", [])) != STAGING_METER_NAMES
    ):
        raise RuntimeError("CloudWatch pilot proof meter identity mismatch")
    if any(proof.get(name) != value for name, value in expected.items()):
        raise RuntimeError("CloudWatch pilot proof provenance mismatch")
    if proof.get("pilotRequestIdsSha256") != expected_pilot_request_ids_sha256:
        raise RuntimeError("CloudWatch pilot proof request ID digest mismatch")
    observation = proof.get("requestIdObservation", {})
    observation_status = observation.get("status")
    if observation_status == "OBSERVED":
        if (
            observation.get("observedRequestIdsSha256") != expected_pilot_request_ids_sha256
            or observation.get("observedRequestIds") != 100
        ):
            raise RuntimeError("CloudWatch pilot proof observed request IDs mismatch")
    elif observation_status == "NOT_OBSERVABLE":
        if (
            observation.get("exclusiveStagingTrafficWindowVerified") is not True
            or not isinstance(observation.get("isolationMethod"), str)
            or not observation["isolationMethod"].strip()
        ):
            raise RuntimeError("CloudWatch pilot proof requires an exclusive traffic window")
    else:
        raise RuntimeError("CloudWatch pilot proof has invalid request ID observability")
    for name in ("observedLlmCalls", "inputTokens", "outputTokens"):
        if not isinstance(proof.get(name), int) or proof[name] <= 0:
            raise RuntimeError(f"CloudWatch pilot proof has invalid {name}")
    if proof["observedLlmCalls"] != 100:
        raise RuntimeError("CloudWatch pilot proof must reconcile exactly 100 LLM calls")
    cost = proof.get("actualCostUsd")
    if not isinstance(cost, (int, float)) or cost <= 0:
        raise RuntimeError("CloudWatch pilot proof has invalid actualCostUsd")
    meter_start = _utc(proof.get("meterWindowStartUtc"), "meterWindowStartUtc")
    meter_end = _utc(proof.get("meterWindowEndUtc"), "meterWindowEndUtc")
    if meter_end <= meter_start:
        raise RuntimeError("CloudWatch pilot proof meter window is invalid")
    current = (validation_now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    if meter_start > execution_start or meter_end < execution_end or meter_end > current:
        raise RuntimeError("CloudWatch pilot proof does not encompass the actual pilot interval")
    returned_models = proof.get("returnedModels")
    if (
        not isinstance(returned_models, list) or not returned_models
        or any(not isinstance(value, str) or not value for value in returned_models)
    ):
        raise RuntimeError("CloudWatch pilot proof has invalid returnedModels")
    return proof


def _utc(value: Any, field: str) -> datetime:
    if not isinstance(value, str):
        raise RuntimeError(f"staging preflight has invalid {field}")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise RuntimeError(f"staging preflight has invalid {field}") from error
    if parsed.tzinfo is None:
        raise RuntimeError(f"staging preflight has invalid {field}")
    return parsed.astimezone(timezone.utc)


def validate_staging_preflight(
    path: Path, *, config: StagingEvalConfig, dataset: dict[str, Any],
    now: datetime | None = None,
) -> dict[str, Any]:
    if not path.is_file():
        raise RuntimeError("structured staging preflight proof is missing")
    proof = json.loads(path.read_text(encoding="utf-8"))
    if proof.get("schemaVersion") != PREFLIGHT_SCHEMA or proof.get("verified") is not True:
        raise RuntimeError("structured staging preflight proof is not verified")
    if any(proof.get(name) != value for name, value in _provenance(config).items()):
        raise RuntimeError("staging preflight provenance mismatch")
    deployment = proof.get("deployment", {})
    health = proof.get("privateHealth", {})
    corpus = proof.get("corpus", {})
    cloudwatch = proof.get("cloudwatch", {})
    approval = proof.get("approval", {})
    window = proof.get("rateLimitWindow", {})
    if deployment.get("host") != config.base_url or deployment.get("backendSha") != config.backend_sha:
        raise RuntimeError("staging deployment readback mismatch")
    deployment_checked = _utc(deployment.get("checkedAtUtc"), "deployment.checkedAtUtc")
    if health.get("status") != "UP":
        raise RuntimeError("staging private health is not UP")
    health_checked = _utc(health.get("checkedAtUtc"), "privateHealth.checkedAtUtc")
    if (
        corpus.get("verified") is not True
        or corpus.get("stores") != 500 or corpus.get("menus") != 5_000
        or corpus.get("sourceSha256") != dataset["metadata"]["corpusSourceSha256"]
    ):
        raise RuntimeError("staging synthetic corpus proof mismatch")
    if cloudwatch.get("readAccessVerified") is not True:
        raise RuntimeError("CloudWatch read access is not verified")
    cloudwatch_checked = _utc(cloudwatch.get("checkedAtUtc"), "cloudwatch.checkedAtUtc")
    if (
        approval.get("approved") is not True
        or approval.get("maxLogicalCalls", 0) < config.max_calls
        or approval.get("maxHttpAttempts", 0) < config.max_http_attempts
        or not isinstance(approval.get("maxCostUsd"), (int, float))
        or approval["maxCostUsd"] <= 0
        or not isinstance(approval.get("maxCostPerHttpAttemptUsd"), (int, float))
        or approval["maxCostPerHttpAttemptUsd"] <= 0
    ):
        raise RuntimeError("staging paid execution approval is insufficient")
    conservative_attempt_cost = (
        config.max_http_attempts * approval["maxCostPerHttpAttemptUsd"]
    )
    if conservative_attempt_cost > approval["maxCostUsd"]:
        raise RuntimeError("approved budget does not cover the HTTP attempt cost cap")
    if window.get("approved") is not True or window.get("maxRequestsPerMinute") != 40:
        raise RuntimeError("shared staging rate-limit window is not approved")
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    for label, checked in (
        ("deployment", deployment_checked), ("private health", health_checked),
        ("CloudWatch", cloudwatch_checked),
    ):
        age_seconds = (current - checked).total_seconds()
        if age_seconds < -300 or age_seconds > 1_800:
            raise RuntimeError(f"staging preflight {label} evidence is stale")
    if not _utc(window.get("startsAtUtc"), "rateLimitWindow.startsAtUtc") <= current <= _utc(
        window.get("endsAtUtc"), "rateLimitWindow.endsAtUtc"
    ):
        raise RuntimeError("staging execution is outside the approved rate-limit window")
    return proof


def _bind_approved_window(config: StagingEvalConfig, preflight: dict[str, Any]) -> StagingEvalConfig:
    window = preflight["rateLimitWindow"]
    return replace(
        config,
        approved_window_start_epoch=_utc(window["startsAtUtc"], "rateLimitWindow.startsAtUtc").timestamp(),
        approved_window_end_epoch=_utc(window["endsAtUtc"], "rateLimitWindow.endsAtUtc").timestamp(),
    )


def _latest_terminal_records(checkpoint: StagingCheckpoint) -> list[dict[str, Any]]:
    terminal: dict[str, dict[str, Any]] = {}
    for record in checkpoint.records():
        if record.get("status") in {
            "success", "format_error", "provider_error_nonretryable", "provider_error_exhausted",
        }:
            terminal[record["requestId"]] = record
    return sorted(terminal.values(), key=lambda row: (row.get("queryId", ""), row.get("repeatIndex", -1)))


def _attempt_telemetry(records: list[dict[str, Any]]) -> dict[str, Any]:
    attempts = [record for record in records if record.get("status") != "attempt_intent"]
    logical = {record.get("requestId") for record in attempts}
    latencies = sorted(float(record.get("latencyMs", 0.0)) for record in attempts)

    def percentile(fraction: float) -> float:
        if not latencies:
            return 0.0
        position = (len(latencies) - 1) * fraction
        lower = int(position)
        upper = min(lower + 1, len(latencies) - 1)
        return latencies[lower] + (latencies[upper] - latencies[lower]) * (position - lower)

    return {
        "httpAttempts": len(attempts),
        "logicalRequests": len(logical),
        "retryAttempts": max(0, len(attempts) - len(logical)),
        "statusBreakdown": dict(sorted(Counter(record.get("status") or "unknown" for record in attempts).items())),
        "httpStatusBreakdown": dict(sorted(Counter(
            str(record.get("providerHttpStatus")) for record in attempts
        ).items())),
        "failureBreakdown": dict(sorted(Counter(
            record.get("failureKind") or "none" for record in attempts
        ).items())),
        "retryable429": sum(record.get("providerHttpStatus") == 429 for record in attempts),
        "retryable5xx": sum(
            isinstance(record.get("providerHttpStatus"), int)
            and 500 <= record["providerHttpStatus"] <= 599 for record in attempts
        ),
        "timeouts": sum(record.get("failureKind") == "timeout" for record in attempts),
        "latencyMs": {
            "mean": sum(latencies) / len(latencies) if latencies else 0.0,
            "p50": percentile(0.50), "p95": percentile(0.95),
            "p99": percentile(0.99), "max": max(latencies, default=0.0),
        },
    }


def _validate_full_cloudwatch_proof(
    path: Path, *, expected: dict[str, Any], http_attempts: int,
    approved_max_cost_usd: float, approved_window_start: datetime,
    approved_window_end: datetime, execution_start: datetime,
    execution_end: datetime, validation_now: datetime | None = None,
) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    proof = json.loads(path.read_text(encoding="utf-8"))
    if proof.get("schemaVersion") != FULL_CLOUDWATCH_PROOF_SCHEMA or proof.get("verified") is not True:
        raise RuntimeError("full CloudWatch proof is not verified")
    if (
        proof.get("meterNamespace") != "MiriYum/Staging"
        or set(proof.get("meterNames", [])) != STAGING_METER_NAMES
    ):
        raise RuntimeError("full CloudWatch proof meter identity mismatch")
    if any(proof.get(name) != value for name, value in expected.items()):
        raise RuntimeError("full CloudWatch proof provenance mismatch")
    if proof.get("httpAttempts") != http_attempts:
        raise RuntimeError("full CloudWatch proof HTTP attempt reconciliation mismatch")
    for name in ("observedLlmCalls", "inputTokens", "outputTokens"):
        if not isinstance(proof.get(name), int) or proof[name] <= 0:
            raise RuntimeError(f"full CloudWatch proof has invalid {name}")
    if proof["observedLlmCalls"] != 10_000:
        raise RuntimeError("full CloudWatch proof must reconcile exactly 10,000 LLM calls")
    if (
        not isinstance(proof.get("actualCostUsd"), (int, float))
        or proof["actualCostUsd"] <= 0 or proof["actualCostUsd"] > approved_max_cost_usd
    ):
        raise RuntimeError("full CloudWatch proof has invalid actualCostUsd")
    meter_start = _utc(proof.get("meterWindowStartUtc"), "meterWindowStartUtc")
    meter_end = _utc(proof.get("meterWindowEndUtc"), "meterWindowEndUtc")
    if meter_end <= meter_start:
        raise RuntimeError("full CloudWatch proof meter window is invalid")
    if meter_start < approved_window_start or meter_end > approved_window_end:
        raise RuntimeError("full CloudWatch proof is outside the approved execution window")
    current = (validation_now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    if meter_start > execution_start or meter_end < execution_end or meter_end > current:
        raise RuntimeError("full CloudWatch proof does not encompass the actual execution interval")
    if (
        not isinstance(proof.get("returnedModels"), list) or not proof["returnedModels"]
        or any(not isinstance(value, str) or not value for value in proof["returnedModels"])
    ):
        raise RuntimeError("full CloudWatch proof has invalid returnedModels")
    return proof


def _write_run_metadata(
    root: Path, *, config: StagingEvalConfig, mode: str, calls: int,
) -> None:
    write_json(root / "staging-run-metadata.json", {
        "schemaVersion": "miriyum-staging-search-run-v1",
        **_provenance(config), "mode": mode, "terminalCalls": calls,
        "baseUrl": config.base_url, "requestedPageSize": 50,
        "requestIntervalSeconds": config.request_interval_seconds,
        "timeoutSeconds": config.timeout_seconds, "maxRetries": config.max_retries,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "secretsRecorded": False,
    })


def _mark_execution_timing(
    root: Path, *, config: StagingEvalConfig, field: str,
) -> dict[str, Any]:
    path = root / "staging-execution-timing.json"
    timing = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {
        "schemaVersion": "miriyum-staging-search-timing-v1", **_provenance(config),
    }
    if timing.get("schemaVersion") != "miriyum-staging-search-timing-v1" or any(
        timing.get(name) != value for name, value in _provenance(config).items()
    ):
        raise RuntimeError("staging execution timing provenance mismatch")
    if field.endswith("StartedAtUtc"):
        timing.setdefault(field, datetime.now(timezone.utc).isoformat())
    else:
        timing[field] = datetime.now(timezone.utc).isoformat()
    write_json(path, timing)
    return timing


def _load_execution_interval(
    root: Path, *, expected: dict[str, Any], start_field: str, end_field: str,
) -> tuple[datetime, datetime]:
    path = root / "staging-execution-timing.json"
    if not path.is_file():
        raise RuntimeError("staging execution timing evidence is missing")
    timing = json.loads(path.read_text(encoding="utf-8"))
    if timing.get("schemaVersion") != "miriyum-staging-search-timing-v1" or any(
        timing.get(name) != value for name, value in expected.items()
    ):
        raise RuntimeError("staging execution timing provenance mismatch")
    started = _utc(timing.get(start_field), start_field)
    ended = _utc(timing.get(end_field), end_field)
    if ended < started:
        raise RuntimeError("staging execution timing interval is invalid")
    return started, ended


def _pilot_gate_evidence(
    dataset: dict[str, Any], records: list[dict[str, Any]],
    attempt_records: list[dict[str, Any]],
) -> dict[str, Any]:
    aggregate = aggregate_staging_evaluation(evaluate_staging_records(dataset, records))
    attempts_by_request = Counter(record["requestId"] for record in attempt_records)
    retries = sum(max(0, count - 1) for count in attempts_by_request.values())
    recovered_failures = Counter(
        record.get("failureKind") or "unknown" for record in attempt_records
        if record.get("status") == "provider_error"
    )
    failure_count = len(records) - aggregate["providerOrFormatSuccessCalls"]
    format_errors = aggregate["formatErrorCalls"]
    hit_at_8 = aggregate["ranking"]["hitRateAt8"]
    negative_fpr = aggregate["negativeFalsePositive"]["rate"]
    return {
        "plannedCalls": 100, "terminalCalls": len(records),
        "providerOrFormatFailures": failure_count, "formatErrors": format_errors,
        "retryAttempts": retries,
        "recoveredFailureBreakdown": dict(sorted(recovered_failures.items())),
        "positiveHitRateAt8": hit_at_8, "negativeFalsePositiveRate": negative_fpr,
        "passed": (
            len(records) == 100 and failure_count == 0 and format_errors == 0
            and retries <= 10 and hit_at_8 >= 0.40 and negative_fpr <= 0.25
        ),
    }


def run_staging_pilot(
    root: Path, *, transport: StagingTransport | None = None,
) -> dict[str, Any]:
    dataset = load_staging_dataset(root)
    config = staging_config_from_environment(dataset["metadata"]["datasetSha256"], mode="pilot")
    config.validate()
    verify_harness_checkout(config)
    preflight = validate_staging_preflight(
        root / "staging-preflight.json", config=config, dataset=dataset,
    )
    config = _bind_approved_window(config, preflight)
    queries = staging_pilot_queries(dataset["queries"], seed=dataset["metadata"]["seed"])
    checkpoint = StagingCheckpoint(root / "staging-calls.checkpoint.jsonl")
    _mark_execution_timing(root, config=config, field="pilotStartedAtUtc")
    try:
        records = run_staging_requests(
            queries, repeats=(0,), config=config, checkpoint=checkpoint,
            transport=transport or UrllibStagingTransport(),
        )
    finally:
        _mark_execution_timing(root, config=config, field="pilotEndedAtUtc")
    attempt_records = validate_staging_checkpoint(checkpoint, config)
    evaluated = evaluate_staging_records(dataset, records)
    aggregate = aggregate_staging_evaluation(evaluated)
    evidence = _pilot_gate_evidence(dataset, records, attempt_records)
    gate = {
        "schemaVersion": "miriyum-staging-search-pilot-gate-v1",
        **_provenance(config), **evidence,
        "pilotRequestIdsSha256": _pilot_request_ids_sha256(dataset, config),
        "fullRunRequiresCloudWatchProof": True,
    }
    write_jsonl(root / "staging-pilot-results.jsonl", evaluated)
    write_json(root / "staging-pilot-aggregate.json", aggregate)
    write_json(root / "staging-pilot-http-telemetry.json", _attempt_telemetry(checkpoint.records()))
    write_json(root / "staging-pilot-gate.json", gate)
    _write_run_metadata(root, config=config, mode="pilot", calls=len(records))
    if not gate["passed"]:
        raise RuntimeError("staging pilot gate failed; paid full run stopped")
    return gate


def run_staging_full(
    root: Path, *, transport: StagingTransport | None = None,
) -> dict[str, Any]:
    dataset = load_staging_dataset(root)
    config = staging_config_from_environment(dataset["metadata"]["datasetSha256"], mode="full")
    config.validate()
    verify_harness_checkout(config)
    preflight = validate_staging_preflight(
        root / "staging-preflight.json", config=config, dataset=dataset,
    )
    config = _bind_approved_window(config, preflight)
    checkpoint = StagingCheckpoint(root / "staging-calls.checkpoint.jsonl")
    validate_pilot_transition(
        root / "staging-pilot-gate.json", checkpoint=checkpoint,
        config=config, dataset=dataset,
    )
    pilot_start, pilot_end = _load_execution_interval(
        root, expected=_provenance(config), start_field="pilotStartedAtUtc",
        end_field="pilotEndedAtUtc",
    )
    pilot_usage = validate_pilot_cloudwatch_proof(
        root / "cloudwatch-pilot-proof.json", expected=_provenance(config),
        expected_pilot_request_ids_sha256=_pilot_request_ids_sha256(dataset, config),
        execution_start=pilot_start, execution_end=pilot_end,
    )
    pilot_meter_start = _utc(pilot_usage["meterWindowStartUtc"], "meterWindowStartUtc")
    pilot_meter_end = _utc(pilot_usage["meterWindowEndUtc"], "meterWindowEndUtc")
    approved_start = _utc(preflight["rateLimitWindow"]["startsAtUtc"], "rateLimitWindow.startsAtUtc")
    approved_end = _utc(preflight["rateLimitWindow"]["endsAtUtc"], "rateLimitWindow.endsAtUtc")
    if pilot_meter_start < approved_start or pilot_meter_end > approved_end:
        raise RuntimeError("pilot CloudWatch proof is outside the approved execution window")
    projected_cost = (
        pilot_usage["actualCostUsd"] / pilot_usage["observedLlmCalls"]
        * config.max_http_attempts
    )
    if projected_cost > preflight["approval"]["maxCostUsd"]:
        raise RuntimeError("pilot projected cost exceeds the approved full-run budget")
    _mark_execution_timing(root, config=config, field="fullStartedAtUtc")
    try:
        records = run_staging_requests(
            dataset["queries"], repeats=range(5), config=config, checkpoint=checkpoint,
            transport=transport or UrllibStagingTransport(),
        )
    finally:
        _mark_execution_timing(root, config=config, field="fullEndedAtUtc")
    if len(records) != 10_000:
        raise RuntimeError("staging full run did not produce the 2,000 x 5 terminal matrix")
    _write_run_metadata(root, config=config, mode="full", calls=len(records))
    return write_staging_report(
        root, dataset=dataset, terminal_records=records,
        attempt_records=checkpoint.records(),
    )


def validate_pilot_transition(
    gate_path: Path, *, checkpoint: StagingCheckpoint,
    config: StagingEvalConfig, dataset: dict[str, Any],
) -> None:
    if not gate_path.is_file():
        raise RuntimeError("successful staging pilot gate is required")
    gate = json.loads(gate_path.read_text(encoding="utf-8"))
    if gate.get("schemaVersion") != "miriyum-staging-search-pilot-gate-v1" or any(
        gate.get(name) != value for name, value in _provenance(config).items()
    ):
        raise RuntimeError("staging pilot gate provenance mismatch")
    result_ledger = validate_staging_checkpoint(checkpoint, config)
    terminal = {
        record["requestId"]: record for record in result_ledger
        if record.get("status") in {"success", "format_error", "provider_error_nonretryable", "provider_error_exhausted"}
    }
    pilot_queries = staging_pilot_queries(dataset["queries"], seed=dataset["metadata"]["seed"])
    expected_pilot_ids = {staging_request_id(query, 0, config) for query in pilot_queries}
    if set(terminal) != expected_pilot_ids or any(record.get("status") != "success" for record in terminal.values()):
        raise RuntimeError("checkpoint does not contain exactly this run's 100 successful pilot calls")
    if gate.get("pilotRequestIdsSha256") != _pilot_request_ids_sha256(dataset, config):
        raise RuntimeError("staging pilot gate request ID digest mismatch")
    recomputed = _pilot_gate_evidence(dataset, list(terminal.values()), result_ledger)
    if recomputed["passed"] is not True or any(gate.get(name) != value for name, value in recomputed.items()):
        raise RuntimeError("staging pilot gate does not match recomputed checkpoint evidence")


def _percent(value: float) -> str:
    return f"{value * 100:.2f}%"


def _render_staging_report(aggregate: dict[str, Any]) -> str:
    ranking = aggregate["ranking"]
    negative = aggregate["negativeFalsePositive"]
    latency = aggregate["latencyMs"]
    stability = aggregate["stability"]
    telemetry = aggregate["httpTelemetry"]
    cloudwatch = aggregate.get("cloudwatchUsage")
    lines = [
        "# MiriYum 스테이징 자연어 검색 대규모 평가",
        "",
        "## 핵심 결과",
        "",
        f"- 효과성 통계 단위: 고유 질의 {aggregate['effectivenessQueries']:,}개 중 반복 0 결과",
        f"- 전체 호출: {aggregate['analyzedCalls']:,}회 (반복 호출은 안정성 분석에만 사용)",
        f"- provider/format 성공: {aggregate['providerOrFormatSuccessCalls']:,}/{aggregate['analyzedCalls']:,} ({_percent(aggregate['providerOrFormatSuccessRate'])})",
        f"- Recall hit @8: {ranking['hitsAt8']:,}/{ranking['queries']:,} ({_percent(ranking['hitRateAt8'])})",
        f"- Recall hit @20: {ranking['hitsAt20']:,}/{ranking['queries']:,} ({_percent(ranking['hitRateAt20'])})",
        f"- Recall hit @50: {ranking['hitsAt50']:,}/{ranking['queries']:,} ({_percent(ranking['hitRateAt50'])})",
        f"- 음성 질의 오탐: {negative['falsePositives']:,}/{negative['queries']:,} ({_percent(negative['rate'])})",
        f"- top-1 안정률: {_percent(stability['top1StableRate'])}, 평균 pairwise Jaccard: {stability['meanPairwiseJaccard']:.4f}",
        f"- 지연시간 p50/p95/p99/max: {latency['p50']:.0f}/{latency['p95']:.0f}/{latency['p99']:.0f}/{latency['max']:.0f}ms",
        f"- HTTP 시도/재시도: {telemetry['httpAttempts']:,}/{telemetry['retryAttempts']:,}",
        f"- HTTP 429/5xx/timeout: {telemetry['retryable429']}/{telemetry['retryable5xx']}/{telemetry['timeouts']}",
        (
            f"- CloudWatch 실제 사용량: {cloudwatch['observedLlmCalls']:,} LLM calls, "
            f"input/output {cloudwatch['inputTokens']:,}/{cloudwatch['outputTokens']:,} tokens, "
            f"USD {cloudwatch['actualCostUsd']:.6f}"
            if cloudwatch else "- CloudWatch 실제 token/비용: PENDING (최종 증거 아님)"
        ),
        "",
        "## 순위 지표",
        "",
        "| K | hit | hit rate | mean Recall | mean nDCG | Wilson 95% |",
        "|---:|---:|---:|---:|---:|---:|",
        *[
            f"| {cutoff} | {ranking[f'hitsAt{cutoff}']}/{ranking['queries']} | "
            f"{_percent(ranking[f'hitRateAt{cutoff}'])} | {ranking[f'meanRecallAt{cutoff}']:.4f} | "
            f"{ranking[f'meanNdcgAt{cutoff}']:.4f} | "
            f"{_percent(aggregate['wilson95'][f'positiveHitAt{cutoff}']['low'])}~{_percent(aggregate['wilson95'][f'positiveHitAt{cutoff}']['high'])} |"
            for cutoff in (1, 3, 5, 8, 20, 50)
        ],
        f"\nMRR: {ranking['meanMrr']:.4f}; 목표 매장 top-1: {ranking['expectedTopHits']}/{ranking['expectedTopQueries']}; "
        f"pairwise 순위: {ranking['pairwiseHits']}/{ranking['pairwiseComparisons']} ({_percent(ranking['pairwiseAccuracy'])}).",
        "",
        "## 해석 한계",
        "",
        "이 평가는 공개 스테이징 HTTP 응답만 보는 black-box 평가입니다. 따라서 LLM 의미 해석 성공률은 관측할 수 없습니다. "
        "애플리케이션 predicate와 후보 생성 손실도 별도로 분해할 수 없으며, 전체 후보는 API가 노출하는 최대 50개까지만 측정합니다. "
        "현재 스테이징 합성 corpus에는 폐점·비공개·과거 버전이 없어 해당 누출률도 측정 대상이 아닙니다.",
        "",
        "## 과거 30질의 × 3회와의 비교",
        "",
        "| 평가 | corpus/통계 단위 | LLM 의미 성공 | one-way DB 호환 | 실제 HTTP @8 |",
        "|---|---|---:|---:|---:|",
        "| 과거 로컬 평가 | 합성 메뉴 30개, 90회 호출 | 81/90 (90.00%) | 64/90 (71.11%) | 측정 안 함 |",
        f"| 이번 staging 평가 | 양성 고유 질의 {ranking['queries']:,}개 반복 0 | 관측 불가 | 관측 불가 | {ranking['hitsAt8']:,}/{ranking['queries']:,} ({_percent(ranking['hitRateAt8'])}) |",
        "",
        "과거 통합 유실 17/90(18.89%p)는 내부 단계가 보이는 로컬 평가 수치입니다. 이번 평가는 corpus와 관측 경계가 달라 직접적인 개선률로 해석하지 않습니다.",
        "",
        "## 양방향 구현·시뮬레이션 구분",
        "",
        "| 결과 종류 | 상태 | 이 보고서에서 주장하는 범위 |",
        "|---|---|---|",
        "| simulated bidirectional | simulated 결과 없음 | 별도 오프라인 재분석을 실제 staging 결과로 사용하지 않음 |",
        "| actual application | staging HTTP 응답 관측 | 배포 SHA의 최종 결과·순위만 측정하며 exact/forward/reverse 내부 경로는 관측 불가 |",
        "",
        "## 필터 위반",
        "",
        "| 필터 | 성공한 적용 질의 | 실패한 적용 질의 | 위반 | 위반률 | Wilson 95% |",
        "|---|---:|---:|---:|---:|---:|",
        *[
            f"| {name} | {row['eligible']} | {row['failedApplicableQueries']} | {row['violations']} | {_percent(row['rate'])} | "
            f"{_percent(row['wilson95']['low'])}~{_percent(row['wilson95']['high'])} |"
            for name, row in aggregate["filterViolationRates"].items()
        ],
        "",
        "## exact/forward/reverse/alias 질의 구성별 @8",
        "",
        "| 구성 | 질의 | @8 적중 | 성공률 | @8 무관 매장 건수 | provider/format 실패 |",
        "|---|---:|---:|---:|---:|---:|",
        *[
            f"| {mode} | {row['queries']} | {row['hitsAt8']} | {_percent(row['hitRateAt8'])} | "
            f"{row['irrelevantReturnedAt8']} | {row['providerOrFormatFailures']} |"
            for mode, row in aggregate["byQueryConstructionMatchMode"].items()
        ],
        "",
        "위 표는 질의를 어떻게 구성했는지에 따른 회수율이며, 애플리케이션 내부에서 실제로 선택된 match mechanism을 뜻하지 않습니다.",
        "",
        "## 질의 유형별 @8",
        "",
        "| 유형 | 질의 | @8 적중 | 오탐 | 형식/제공자 실패 |",
        "|---|---:|---:|---:|---:|",
    ]
    for query_type, row in aggregate["byQueryType"].items():
        lines.append(
            f"| {query_type} | {row['queries']} | {row['hitsAt8']} | "
            f"{row['falsePositives']} | {row['providerOrFormatFailures']} |"
        )
    return "\n".join(lines) + "\n"


def write_staging_report(
    root: Path, *, dataset: dict[str, Any], terminal_records: list[dict[str, Any]],
    attempt_records: list[dict[str, Any]] | None = None,
    cloudwatch_usage: dict[str, Any] | None = None,
) -> dict[str, Any]:
    evaluated = evaluate_staging_records(dataset, terminal_records)
    aggregate = aggregate_staging_evaluation(evaluated)
    aggregate["httpTelemetry"] = _attempt_telemetry(attempt_records or terminal_records)
    aggregate["reportStatus"] = (
        "FINAL_WITH_CLOUDWATCH" if len(terminal_records) == 10_000 and cloudwatch_usage
        else "HTTP_MATRIX_COMPLETE_COST_PENDING" if len(terminal_records) == 10_000
        else "PILOT" if len(terminal_records) == 100 else "PARTIAL"
    )
    aggregate["cloudwatchUsage"] = cloudwatch_usage
    result_path = write_jsonl(root / "staging-results.jsonl", evaluated)
    aggregate_path = write_json(root / "staging-aggregate.json", aggregate)
    report_path = root / "report.md"
    report_path.write_text(_render_staging_report(aggregate), encoding="utf-8")
    major = [
        path for path in root.rglob("*")
        if path.is_file() and path.name not in {"sha256.json", "dataset-sha256.json"}
    ]
    hashes = {
        path.relative_to(root).as_posix(): sha256(path.read_bytes()).hexdigest()
        for path in sorted(set(major + [result_path, aggregate_path, report_path]))
    }
    write_json(root / "sha256.json", hashes)
    return aggregate


def report_from_checkpoint(root: Path) -> dict[str, Any]:
    dataset = load_staging_dataset(root)
    checkpoint = StagingCheckpoint(root / "staging-calls.checkpoint.jsonl")
    all_records = checkpoint.records()
    records = _latest_terminal_records(checkpoint)
    if not records:
        raise RuntimeError("staging checkpoint has no terminal records")
    provenance = {
        name: records[0].get(name) for name in (
            "runId", "backendSha", "harnessSha", "datasetSha256", "requestedModel",
            "temperature", "systemInstructionSha256", "jsonSchemaSha256",
        )
    }
    if provenance["datasetSha256"] != dataset["metadata"]["datasetSha256"] or any(
        any(record.get(name) != value for name, value in provenance.items()) for record in records
    ):
        raise RuntimeError("staging report checkpoint provenance mismatch")
    telemetry = _attempt_telemetry(all_records)
    preflight_path = root / "staging-preflight.json"
    full_proof_path = root / "cloudwatch-full-proof.json"
    if len(records) == 10_000 and full_proof_path.is_file() and not preflight_path.is_file():
        raise RuntimeError("staging preflight is required to verify the full-run cost cap")
    preflight = (
        json.loads(preflight_path.read_text(encoding="utf-8"))
        if preflight_path.is_file() else None
    )
    if preflight is not None and (
        preflight.get("schemaVersion") != PREFLIGHT_SCHEMA
        or preflight.get("verified") is not True
        or any(preflight.get(name) != value for name, value in provenance.items())
    ):
        raise RuntimeError("staging report preflight provenance mismatch")
    approved_max_cost = preflight.get("approval", {}).get("maxCostUsd") if preflight else None
    if len(records) == 10_000 and full_proof_path.is_file() and (
        not isinstance(approved_max_cost, (int, float)) or approved_max_cost <= 0
    ):
        raise RuntimeError("staging preflight has no valid approved full-run cost cap")
    execution_start, execution_end = (
        _load_execution_interval(
            root, expected=provenance, start_field="pilotStartedAtUtc", end_field="fullEndedAtUtc",
        ) if len(records) == 10_000 and full_proof_path.is_file() else (
            datetime.min.replace(tzinfo=timezone.utc), datetime.min.replace(tzinfo=timezone.utc)
        )
    )
    cloudwatch = _validate_full_cloudwatch_proof(
        full_proof_path, expected=provenance,
        http_attempts=telemetry["httpAttempts"],
        approved_max_cost_usd=float(approved_max_cost) if isinstance(approved_max_cost, (int, float)) else 0.0,
        approved_window_start=_utc(preflight["rateLimitWindow"]["startsAtUtc"], "rateLimitWindow.startsAtUtc") if preflight else datetime.min.replace(tzinfo=timezone.utc),
        approved_window_end=_utc(preflight["rateLimitWindow"]["endsAtUtc"], "rateLimitWindow.endsAtUtc") if preflight else datetime.max.replace(tzinfo=timezone.utc),
        execution_start=execution_start, execution_end=execution_end,
    ) if len(records) == 10_000 else None
    return write_staging_report(
        root, dataset=dataset, terminal_records=records,
        attempt_records=all_records, cloudwatch_usage=cloudwatch,
    )
