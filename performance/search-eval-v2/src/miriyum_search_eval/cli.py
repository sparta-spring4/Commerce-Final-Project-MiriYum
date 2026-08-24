from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
from typing import Any

from .artifacts import write_dataset_artifacts, write_json, write_jsonl, write_sha256_manifest
from .catalog import DatasetConfig, generate_dataset
from .evaluation import aggregate_evaluation, derive_call_diagnostics, evaluate_call
from .hybrid_search import (
    aggregate_hybrid_comparison,
    evaluate_hybrid_variants,
    prepare_hybrid_catalog,
)
from .metrics import ranking_metrics
from .matching import prepare_catalog
from .reporting import render_report
from .runner import (
    ATTRIBUTE_EVIDENCE_SYSTEM_INSTRUCTION,
    CheckpointStore,
    EvalConfig,
    JSON_SCHEMA,
    OpenAIChatTransport,
    deterministic_request_id,
    deterministic_request_id_from_fingerprint,
    run_requests,
)
from .structured_search import (
    aggregate_structured_comparison,
    evaluate_structured_variants,
    prepare_structured_catalog,
)
from .validation import validate_dataset
from .workflow import (
    canonicalize_equivalent_calls,
    evaluation_config_from_pilot,
    migrate_unchanged_calls,
    model_comparison_summary,
    paired_reanalysis_summary,
    paid_execution_summary,
    pilot_gate,
    prompt_experiment_gate,
    stratified_pilot_queries,
    validate_checkpoint_matrix,
)


PRE_ISSUE_616 = "pre-issue-616"
ISSUE_616_MOST_SPECIFIC = "issue-616-most-specific"
PREDICATE_VARIANTS = (PRE_ISSUE_616, ISSUE_616_MOST_SPECIFIC)
STRUCTURED_EVAL_BASE_SHA = "229b5aa765873c141270e257be65aedae72b9f86"
HYBRID_STRUCTURED_RESULTS_SHA = "9df2560af0cd46ab012c69f298a776126db0bbbbfcb1ab052ecb820249b57ba0"
HYBRID_EMBEDDING_CHECKPOINT_SHA = "f9cabb92c0613e84730bd3aec343f41be1b2444e21c2ac541a0f17aa0db59d58"


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[4]


def _commit_sha() -> str:
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=_repo_root(), text=True).strip()


def _config() -> EvalConfig:
    return EvalConfig()


def _prompt_experiment_config() -> EvalConfig:
    # Frozen before the pilot so its repeat-0 calls remain reusable in the full run.
    return EvalConfig(
        estimated_input_tokens=400,
        estimated_output_tokens=60,
        system_instruction_override=ATTRIBUTE_EVIDENCE_SYSTEM_INSTRUCTION,
    )


def _gpt54mini_comparison_config(system_instruction: str) -> EvalConfig:
    return EvalConfig(
        model="gpt-5.4-mini",
        max_calls=100,
        cost_cap_usd=0.10,
        estimated_input_tokens=450,
        estimated_output_tokens=80,
        input_usd_per_million=0.75,
        output_usd_per_million=4.50,
        system_instruction_override=system_instruction,
        reasoning_effort="none",
    )


def _dataset() -> dict[str, Any]:
    return generate_dataset(DatasetConfig(seed=20260823, schema_version="miriyum-search-eval-v2.1"))


def _metadata(
    dataset: dict[str, Any], config: EvalConfig,
    records: list[dict[str, Any]] | None = None,
    paid_execution: dict[str, Any] | None = None,
) -> dict[str, Any]:
    application_sources = {
        "interpreter": _repo_root() / "backend/src/main/java/com/miriyum/domain/search/expansion/OpenAiSearchConceptInterpreter.java",
        "predicates": _repo_root() / "backend/src/main/java/com/miriyum/domain/search/repository/IntegratedStoreSearchPredicates.java",
        "repository": _repo_root() / "backend/src/main/java/com/miriyum/domain/search/repository/IntegratedStoreSearchRepository.java",
    }
    from hashlib import sha256
    metadata = {
        "schemaVersion": dataset["metadata"]["schemaVersion"],
        "seed": dataset["metadata"]["seed"],
        "datasetSha256": dataset["metadata"]["datasetSha256"],
        "commitSha": _commit_sha(),
        "executedAtUtc": datetime.now(timezone.utc).isoformat(),
        "requestedModel": config.model,
        "returnedModels": sorted({record["returnedModel"] for record in (records or []) if record.get("returnedModel")}),
        "temperature": config.temperature,
        "reasoningEffort": config.reasoning_effort,
        "maxOutputTokens": config.max_tokens,
        "systemInstruction": config.system_instruction(),
        "jsonSchema": JSON_SCHEMA,
        "requestFingerprint": config.fingerprint(),
        "applicationSourceSha256": {
            name: sha256(path.read_bytes()).hexdigest()
            for name, path in application_sources.items()
        },
        "actualApplicationPredicate": "bidirectional-current",
        "executionPredicateVariant": ISSUE_616_MOST_SPECIFIC,
        "legacyCounterfactualPredicate": "pre-issue-589-one-way",
        "pricingSources": [
            "https://developers.openai.com/api/docs/models/gpt-4o-mini",
            "https://developers.openai.com/api/docs/models/gpt-5.4-mini",
            "https://developers.openai.com/api/docs/models/text-embedding-3-small",
            "https://developers.openai.com/api/docs/models/text-embedding-3-large",
        ],
    }
    if paid_execution is not None:
        metadata["paidExecution"] = paid_execution
    return metadata


def _validate_prompt_full_provenance(
    *, gate: dict[str, Any], metadata: dict[str, Any], dataset_sha: str,
    config: EvalConfig, pilot_queries: list[dict[str, Any]], checkpoint: CheckpointStore,
) -> None:
    expected_pilot_ids = sorted(query["id"] for query in pilot_queries)
    if (
        gate.get("datasetSha256") != dataset_sha
        or gate.get("requestFingerprint") != config.fingerprint()
        or gate.get("pilotQueryIds") != expected_pilot_ids
        or metadata.get("datasetSha256") != dataset_sha
        or metadata.get("requestFingerprint") != config.fingerprint()
    ):
        raise RuntimeError("prompt pilot gate provenance does not match this full experiment")
    for query in pilot_queries:
        request_id = deterministic_request_id(dataset_sha, query["id"], 0, config)
        record = checkpoint.get(request_id)
        if record is None or record.get("status") != "success":
            raise RuntimeError("prompt pilot checkpoint is incomplete or unsuccessful")


def generate(root: Path) -> None:
    dataset = _dataset()
    validation = validate_dataset(dataset)
    if validation["errors"]:
        raise RuntimeError(f"dataset validation failed: {validation['errors']}")
    files = write_dataset_artifacts(dataset, root)
    files.append(write_json(root / "validation.json", validation))
    write_json(root / "run-metadata.json", _metadata(dataset, _config()))
    write_sha256_manifest(files, root / "dataset-sha256.json")


def _api_key() -> str:
    value = os.environ.get("OPENAI_API_KEY", "")
    if not value:
        raise RuntimeError("OPENAI_API_KEY is not present in the environment")
    return value


def _evaluate(
    dataset: dict[str, Any], records: list[dict[str, Any]],
    predicate_variant: str = ISSUE_616_MOST_SPECIFIC,
) -> list[dict[str, Any]]:
    if predicate_variant not in PREDICATE_VARIANTS:
        raise ValueError(f"unknown predicate variant: {predicate_variant}")
    query_by_id = {query["id"]: query for query in dataset["queries"]}
    retrieval_cache: dict[tuple[Any, ...], Any] = {}
    prepared_catalog = prepare_catalog(
        families=dataset["families"], stores=dataset["stores"], menus=dataset["menus"],
    )
    return [
        evaluate_call(
            dataset, query_by_id[record["queryId"]], record,
            retrieval_cache=retrieval_cache, prepared_catalog=prepared_catalog,
            guarded_original_reverse=predicate_variant == ISSUE_616_MOST_SPECIFIC,
        )
        for record in records
    ]


def pilot(root: Path) -> None:
    generate(root)
    dataset = _dataset()
    config = _config()
    queries = stratified_pilot_queries(dataset["queries"], seed=dataset["metadata"]["seed"])
    checkpoint = CheckpointStore(root / "calls.checkpoint.jsonl")
    transport = OpenAIChatTransport(_api_key())
    records = run_requests(
        queries, repeats=(0,), dataset_sha=dataset["metadata"]["datasetSha256"],
        config=config, checkpoint=checkpoint, transport=transport,
    )
    calls = _evaluate(dataset, records, ISSUE_616_MOST_SPECIFIC)
    paid_execution = paid_execution_summary([], checkpoint.records(), analyzed_calls=len(records))
    write_jsonl(root / "pilot-results.jsonl", calls)
    write_json(root / "pilot-aggregate.json", aggregate_evaluation(calls))

    class NoCallTransport:
        calls = 0
        def __call__(self, body, timeout_seconds):
            self.calls += 1
            raise AssertionError("resume attempted a duplicate provider call")

    resume_transport = NoCallTransport()
    run_requests(
        queries, repeats=(0,), dataset_sha=dataset["metadata"]["datasetSha256"],
        config=config, checkpoint=checkpoint, transport=resume_transport,
    )
    gate = pilot_gate(calls, projected_call_count=10_000, cost_cap_usd=config.cost_cap_usd, resume_duplicate_calls=resume_transport.calls)
    write_json(root / "pilot-gate.json", gate)
    write_json(root / "run-metadata.json", _metadata(dataset, config, records, paid_execution))
    if not gate["passed"]:
        raise RuntimeError(f"pilot gate failed: {gate['fatalReasons']}")


def prompt_pilot(root: Path, source: Path) -> None:
    """Run one paired call for the fixed 100-query prompt experiment."""
    generate(root)
    dataset = _dataset()
    config = _prompt_experiment_config()
    queries = stratified_pilot_queries(
        dataset["queries"], seed=dataset["metadata"]["seed"],
    )
    pilot_ids = {query["id"] for query in queries}

    source_checkpoint = CheckpointStore(source / "calls.canonical.checkpoint.jsonl")
    source_metadata = json.loads((source / "run-metadata.json").read_text(encoding="utf-8"))
    if source_metadata.get("datasetSha256") != dataset["metadata"]["datasetSha256"]:
        raise RuntimeError("source baseline dataset SHA does not match prompt experiment")
    source_records = [
        record for record in source_checkpoint.records()
        if record.get("queryId") in pilot_ids and record.get("repeatIndex") == 0
    ]
    if len(source_records) != 100 or any(record.get("status") != "success" for record in source_records):
        raise RuntimeError("source artifact must contain 100 successful paired baseline calls")
    if {record.get("requestFingerprint") for record in source_records} != {
        source_metadata.get("requestFingerprint")
    }:
        raise RuntimeError("source baseline request fingerprint does not match its metadata")
    baseline_calls = _evaluate(dataset, source_records, ISSUE_616_MOST_SPECIFIC)

    checkpoint = CheckpointStore(root / "calls.prompt-experiment.checkpoint.jsonl")
    records = run_requests(
        queries, repeats=(0,), dataset_sha=dataset["metadata"]["datasetSha256"],
        config=config, checkpoint=checkpoint, transport=OpenAIChatTransport(_api_key()),
    )
    experiment_calls = _evaluate(dataset, records, ISSUE_616_MOST_SPECIFIC)

    class NoCallTransport:
        calls = 0

        def __call__(self, body, timeout_seconds):
            self.calls += 1
            raise AssertionError("resume attempted a duplicate provider call")

    resume_transport = NoCallTransport()
    run_requests(
        queries, repeats=(0,), dataset_sha=dataset["metadata"]["datasetSha256"],
        config=config, checkpoint=checkpoint, transport=resume_transport,
    )
    standard_gate = pilot_gate(
        experiment_calls, projected_call_count=10_000,
        cost_cap_usd=config.cost_cap_usd,
        resume_duplicate_calls=resume_transport.calls,
    )
    paired_gate = prompt_experiment_gate(
        baseline_calls, experiment_calls, minimum_sensory_gain=3,
    )
    gate = {
        "schemaVersion": "miriyum-search-prompt-pilot-gate-v1",
        "passed": standard_gate["passed"] and paired_gate["passed"],
        "datasetSha256": dataset["metadata"]["datasetSha256"],
        "requestFingerprint": config.fingerprint(),
        "pilotQueryIds": sorted(pilot_ids),
        "standardPilotGate": standard_gate,
        "pairedExperimentGate": paired_gate,
    }
    paid_execution = paid_execution_summary(
        [], records, analyzed_calls=len(records),
    )
    write_jsonl(root / "baseline-pilot-results.jsonl", baseline_calls)
    write_jsonl(root / "prompt-pilot-results.jsonl", experiment_calls)
    write_json(root / "baseline-pilot-aggregate.json", aggregate_evaluation(baseline_calls))
    write_json(root / "prompt-pilot-aggregate.json", aggregate_evaluation(experiment_calls))
    write_json(root / "prompt-pilot-gate.json", gate)
    metadata = _metadata(dataset, config, records, paid_execution)
    metadata["sourceBaselineArtifact"] = str(source.resolve())
    metadata["experimentStatus"] = "prompt-only-pilot-not-production"
    write_json(root / "run-metadata.json", metadata)
    if not gate["passed"]:
        raise RuntimeError(
            "prompt pilot gate failed: "
            f"standard={standard_gate['fatalReasons']}, paired={paired_gate['fatalReasons']}"
        )


def prompt_full_run(root: Path) -> None:
    """Complete 2,000 x 5 only after the paired prompt pilot passes."""
    gate = json.loads((root / "prompt-pilot-gate.json").read_text(encoding="utf-8"))
    if gate.get("passed") is not True:
        raise RuntimeError("successful paired prompt pilot gate is required")
    dataset = _dataset()
    config = _prompt_experiment_config()
    metadata = json.loads((root / "run-metadata.json").read_text(encoding="utf-8"))
    expected_pilot = stratified_pilot_queries(
        dataset["queries"], seed=dataset["metadata"]["seed"],
    )
    checkpoint = CheckpointStore(root / "calls.prompt-experiment.checkpoint.jsonl")
    _validate_prompt_full_provenance(
        gate=gate, metadata=metadata,
        dataset_sha=dataset["metadata"]["datasetSha256"], config=config,
        pilot_queries=expected_pilot, checkpoint=checkpoint,
    )
    recovery = checkpoint.quarantine_unbilled_retryable_failures(
        root / "retryable-failures.audit.jsonl",
    )
    write_json(root / "checkpoint-recovery.json", recovery)
    records = run_requests(
        dataset["queries"], repeats=range(5),
        dataset_sha=dataset["metadata"]["datasetSha256"], config=config,
        checkpoint=checkpoint, transport=OpenAIChatTransport(_api_key()),
    )
    validate_checkpoint_matrix(
        queries=dataset["queries"], records=records, repeats=range(5),
    )
    calls = _evaluate(dataset, records, ISSUE_616_MOST_SPECIFIC)
    write_jsonl(root / "prompt-results.jsonl", calls)
    write_json(root / "prompt-aggregate.json", aggregate_evaluation(calls))
    paid_execution = paid_execution_summary(
        [], checkpoint.records(), analyzed_calls=len(records),
    )
    metadata = _metadata(dataset, config, records, paid_execution)
    metadata["experimentStatus"] = "prompt-only-full-not-production"
    write_json(root / "run-metadata.json", metadata)


def model_compare(root: Path, source: Path) -> None:
    """Pair the frozen attribute-prompt pilot against GPT-5.4 mini."""
    generate(root)
    dataset = _dataset()
    dataset_sha = dataset["metadata"]["datasetSha256"]
    baseline_config = _prompt_experiment_config()
    comparison_config = _gpt54mini_comparison_config(
        baseline_config.system_instruction(),
    )
    queries = stratified_pilot_queries(
        dataset["queries"], seed=dataset["metadata"]["seed"],
    )

    source_gate = json.loads((source / "prompt-pilot-gate.json").read_text(encoding="utf-8"))
    source_metadata = json.loads((source / "run-metadata.json").read_text(encoding="utf-8"))
    if (
        source_gate.get("passed") is not True
        or source_gate.get("datasetSha256") != dataset_sha
        or source_gate.get("requestFingerprint") != baseline_config.fingerprint()
        or source_metadata.get("systemInstruction") != comparison_config.system_instruction()
    ):
        raise RuntimeError("source prompt pilot does not match the model comparison contract")
    source_checkpoint = CheckpointStore(source / "calls.prompt-experiment.checkpoint.jsonl")
    baseline_records = []
    for query in queries:
        request_id = deterministic_request_id(dataset_sha, query["id"], 0, baseline_config)
        record = source_checkpoint.get(request_id)
        if record is None or record.get("status") != "success":
            raise RuntimeError("source prompt pilot baseline is incomplete")
        baseline_records.append(record)
    baseline_calls = _evaluate(dataset, baseline_records, ISSUE_616_MOST_SPECIFIC)

    checkpoint = CheckpointStore(root / "calls.gpt-5.4-mini.checkpoint.jsonl")
    transport = OpenAIChatTransport(_api_key())
    preflight = run_requests(
        queries[:1], repeats=(0,), dataset_sha=dataset_sha,
        config=comparison_config, checkpoint=checkpoint, transport=transport,
    )
    if preflight[0].get("status") != "success":
        write_jsonl(root / "gpt-5.4-mini-preflight.jsonl", preflight)
        write_json(
            root / "run-metadata.json",
            _metadata(dataset, comparison_config, preflight,
                      paid_execution_summary([], preflight, analyzed_calls=1)),
        )
        hash_artifact(root)
        raise RuntimeError(
            f"GPT-5.4 mini preflight failed: {preflight[0].get('status')} / "
            f"{preflight[0].get('providerHttpStatus')}"
        )

    comparison_records = run_requests(
        queries, repeats=(0,), dataset_sha=dataset_sha,
        config=comparison_config, checkpoint=checkpoint, transport=transport,
    )
    comparison_calls = _evaluate(
        dataset, comparison_records, ISSUE_616_MOST_SPECIFIC,
    )
    summary = model_comparison_summary(baseline_calls, comparison_calls)
    summary.update({
        "baselineRequestedModel": baseline_config.model,
        "comparisonRequestedModel": comparison_config.model,
        "comparisonReturnedModels": sorted({
            record.get("returnedModel") for record in comparison_records
            if record.get("returnedModel")
        }),
    })
    write_jsonl(root / "baseline-gpt-4o-mini-results.jsonl", baseline_calls)
    write_jsonl(root / "comparison-gpt-5.4-mini-results.jsonl", comparison_calls)
    write_json(root / "baseline-gpt-4o-mini-aggregate.json", aggregate_evaluation(baseline_calls))
    write_json(root / "comparison-gpt-5.4-mini-aggregate.json", aggregate_evaluation(comparison_calls))
    write_json(root / "model-comparison-summary.json", summary)
    paid_execution = paid_execution_summary(
        [], comparison_records, analyzed_calls=len(comparison_records),
    )
    metadata = _metadata(dataset, comparison_config, comparison_records, paid_execution)
    metadata.update({
        "experimentStatus": "paired-100-query-model-comparison-not-production",
        "baselineSourceArtifact": str(source.resolve()),
        "baselineRequestFingerprint": baseline_config.fingerprint(),
    })
    write_json(root / "run-metadata.json", metadata)
    hash_artifact(root)


def migrate_pilot(root: Path, source: Path) -> None:
    generate(root)
    dataset = _dataset()
    old_queries = [json.loads(line) for line in (source / "queries.jsonl").read_text(encoding="utf-8").splitlines() if line]
    old_records = [json.loads(line) for line in (source / "calls.checkpoint.jsonl").read_text(encoding="utf-8").splitlines() if line]
    pilot_ids = {query["id"] for query in stratified_pilot_queries(dataset["queries"], seed=dataset["metadata"]["seed"])}
    old_records = [record for record in old_records if record.get("queryId") in pilot_ids and record.get("repeatIndex") == 0]
    result = migrate_unchanged_calls(
        old_queries=old_queries, new_queries=dataset["queries"], old_records=old_records,
        new_dataset_sha=dataset["metadata"]["datasetSha256"], config=_config(),
        destination=CheckpointStore(root / "calls.checkpoint.jsonl"),
    )
    result["sourceArtifact"] = str(source.resolve())
    result["sourceCalls"] = len(old_records)
    write_json(root / "pilot-migration.json", result)


def migrate_checkpoint(root: Path, source: Path) -> None:
    """Reuse every unchanged paid response under the v2.1 dataset identity."""
    generate(root)
    dataset = _dataset()
    old_queries = [
        json.loads(line) for line in (source / "queries.jsonl").read_text(encoding="utf-8").splitlines()
        if line
    ]
    old_records = CheckpointStore(source / "calls.canonical.checkpoint.jsonl").records()
    matrix = validate_checkpoint_matrix(
        queries=old_queries, records=old_records, repeats=range(5),
    )
    pilot_ids = {
        query["id"] for query in stratified_pilot_queries(old_queries, seed=dataset["metadata"]["seed"])
    }
    pilot_records = [
        record for record in old_records
        if record.get("queryId") in pilot_ids and record.get("repeatIndex") == 0
    ]
    if len(pilot_records) != 100 or any(record.get("status") != "success" for record in pilot_records):
        raise RuntimeError("source checkpoint does not contain the frozen successful 100-call pilot")
    config = EvalConfig(
        estimated_input_tokens=round(sum(int(record["inputTokens"]) for record in pilot_records) / 100),
        estimated_output_tokens=round(sum(int(record["outputTokens"]) for record in pilot_records) / 100),
    )
    source_fingerprints = {record.get("requestFingerprint") for record in old_records}
    if source_fingerprints != {config.fingerprint()}:
        raise RuntimeError("source checkpoint request fingerprint does not match reconstructed config")
    result = migrate_unchanged_calls(
        old_queries=old_queries, new_queries=dataset["queries"], old_records=old_records,
        new_dataset_sha=dataset["metadata"]["datasetSha256"], config=config,
        destination=CheckpointStore(root / "calls.canonical.checkpoint.jsonl"),
    )
    if result != {"migrated": 10_000, "changedOrMissing": 0}:
        raise RuntimeError(f"full checkpoint migration incomplete: {result}")
    source_metadata = json.loads((source / "run-metadata.json").read_text(encoding="utf-8"))
    source_gate = json.loads((source / "pilot-gate.json").read_text(encoding="utf-8"))
    write_json(root / "pilot-gate.json", source_gate)
    migration = {
        **result,
        "checkpointMatrix": matrix,
        "sourceArtifact": str(source.resolve()),
        "sourceDatasetSha256": source_metadata["datasetSha256"],
        "destinationDatasetSha256": dataset["metadata"]["datasetSha256"],
        "newProviderCalls": 0,
        "incrementalCostUsd": 0.0,
    }
    write_json(root / "checkpoint-migration.json", migration)
    metadata = _metadata(
        dataset, config, old_records,
        paid_execution=paid_execution_summary([], CheckpointStore(root / "calls.canonical.checkpoint.jsonl").records(), analyzed_calls=10_000),
    )
    metadata["executionPredicateVariant"] = source_metadata.get("analysisPredicateVariant", ISSUE_616_MOST_SPECIFIC)
    metadata["checkpointReuse"] = {
        **migration,
        "sourcePaidExecution": source_metadata.get("paidExecution", {}),
    }
    _pin_source_checkpoint_sha256(
        metadata, root / "calls.canonical.checkpoint.jsonl",
    )
    write_json(root / "run-metadata.json", metadata)


def full_run(root: Path) -> None:
    gate = json.loads((root / "pilot-gate.json").read_text(encoding="utf-8"))
    if gate.get("passed") is not True:
        raise RuntimeError("successful pilot gate is required before the main run")
    dataset = _dataset()
    pilot_calls = [
        json.loads(line)
        for line in (root / "pilot-results.jsonl").read_text(encoding="utf-8").splitlines()
        if line
    ]
    config = evaluation_config_from_pilot(pilot_calls)
    raw_checkpoint = CheckpointStore(root / "calls.checkpoint.jsonl")
    checkpoint = CheckpointStore(root / "calls.canonical.checkpoint.jsonl")
    canonicalization = canonicalize_equivalent_calls(
        queries=dataset["queries"], records=raw_checkpoint.records(),
        dataset_sha=dataset["metadata"]["datasetSha256"],
        source_configs=(_config(), config), destination_config=config,
        preferred_request_ids={call["requestId"] for call in pilot_calls},
        destination=checkpoint,
    )
    write_json(root / "checkpoint-canonicalization.json", canonicalization)
    records = run_requests(
        dataset["queries"], repeats=range(5), dataset_sha=dataset["metadata"]["datasetSha256"],
        config=config, checkpoint=checkpoint, transport=OpenAIChatTransport(_api_key()),
    )
    calls = _evaluate(dataset, records, ISSUE_616_MOST_SPECIFIC)
    paid_execution = paid_execution_summary(
        raw_checkpoint.records(), checkpoint.records(), analyzed_calls=len(records),
    )
    write_jsonl(root / "results.jsonl", calls)
    write_json(root / "aggregate.json", aggregate_evaluation(calls))
    metadata = _metadata(dataset, config, records, paid_execution)
    _pin_source_checkpoint_sha256(metadata, checkpoint.path)
    write_json(root / "run-metadata.json", metadata)


def embeddings(root: Path) -> None:
    from .embeddings import OpenAIEmbeddingTransport, embed_texts, topk_cosine

    dataset = _dataset()
    api_key = _api_key()
    menu_ids = [menu["id"] for menu in dataset["menus"]]
    menu_texts = [f"{menu['name']} {menu['description']} {' '.join(menu['tags'])}" for menu in dataset["menus"]]
    query_ids = [query["id"] for query in dataset["queries"]]
    query_texts = [query["text"] for query in dataset["queries"]]
    menu_by_id = {menu["id"]: menu for menu in dataset["menus"]}
    store_by_id = {store["id"]: store for store in dataset["stores"]}
    results = {}
    for model in ("text-embedding-3-small", "text-embedding-3-large"):
        transport = OpenAIEmbeddingTransport(api_key)
        corpus = embed_texts(ids=menu_ids, texts=menu_texts, model=model, artifact_dir=root / "embeddings", transport=transport)
        queries = embed_texts(ids=query_ids, texts=query_texts, model=model, artifact_dir=root / "embeddings", transport=transport)
        indices, _ = topk_cosine(queries.vectors, corpus.vectors, k=200)
        metric_rows = []
        for query, row in zip(dataset["queries"], indices):
            ranked_stores = []
            for index in row:
                menu = dataset["menus"][int(index)]
                store = store_by_id[menu["storeId"]]
                if store["verificationStatus"] != "APPROVED" or store["operationStatus"] == "CLOSED" or menu["retired"] or menu["visibility"] != "VISIBLE":
                    continue
                filters = query["filters"]
                if filters.get("region") and store["region"] != filters["region"]:
                    continue
                if filters.get("ambience") and store["ambience"] != filters["ambience"]:
                    continue
                if filters.get("maxPrice") is not None and menu["price"] > filters["maxPrice"]:
                    continue
                if store["id"] not in ranked_stores:
                    ranked_stores.append(store["id"])
                if len(ranked_stores) == 8:
                    break
            metric_rows.append(ranking_metrics(ranked_stores, query["gold"]["storeIds"]))
        keys = metric_rows[0].keys()
        results[model] = {
            "ranking": {key: sum(row[key] for row in metric_rows) / len(metric_rows) for key in keys},
            "usage": {"inputTokens": corpus.input_tokens + queries.input_tokens, "costUsd": corpus.cost_usd + queries.cost_usd},
            "requestedModel": model,
            "returnedModels": sorted(set(corpus.returned_models + queries.returned_models)),
            "corpusSize": len(menu_ids), "queryCount": len(query_ids), "similarityImplementation": "numpy-matmul-argpartition",
            "providerBatchCalls": sum(
                1 for line in (root / "embeddings" / model / "embedding-checkpoint.jsonl").read_text(encoding="utf-8").splitlines()
                if line
            ),
        }
    write_json(root / "embedding-aggregate.json", results)


def reanalyze(root: Path, predicate_variant: str) -> None:
    """Recompute deterministic metrics from paid checkpoints without provider calls."""
    metadata_path = root / "run-metadata.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    _validate_reanalysis_variant(metadata, predicate_variant)
    checkpoint_path = root / "calls.canonical.checkpoint.jsonl"
    _validate_source_checkpoint_sha256(metadata, checkpoint_path)
    dataset = _dataset()
    checkpoint = CheckpointStore(checkpoint_path)
    records = checkpoint.records()
    _validate_reanalysis_provenance(metadata, dataset, records)
    validate_checkpoint_matrix(
        queries=dataset["queries"], records=records, repeats=range(5),
    )
    baseline_calls = _evaluate(dataset, records, PRE_ISSUE_616)
    comparison_calls = _evaluate(dataset, records, ISSUE_616_MOST_SPECIFIC)
    paired_summary = paired_reanalysis_summary(baseline_calls, comparison_calls)
    _write_paired_reanalysis_outputs(
        root=root,
        baseline_calls=baseline_calls,
        baseline_aggregate=aggregate_evaluation(baseline_calls),
        comparison_calls=comparison_calls,
        comparison_aggregate=aggregate_evaluation(comparison_calls),
        summary=paired_summary,
    )
    stamp_reanalysis(
        root,
        predicate_variant,
        paired_summary=paired_summary,
        checkpoint_path=checkpoint.path,
    )


def _load_validated_structured_baseline(
    root: Path, records: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    path = root / "reanalysis" / ISSUE_616_MOST_SPECIFIC / "results.jsonl"
    if not path.is_file():
        raise RuntimeError("validated Issue #616 baseline results are missing")
    baseline_by_request: dict[str, dict[str, Any]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        call = json.loads(line)
        request_id = call.get("requestId")
        if not isinstance(request_id, str) or request_id in baseline_by_request:
            raise RuntimeError("baseline result request IDs are invalid or duplicated")
        baseline_by_request[request_id] = call
    if set(baseline_by_request) != {record.get("requestId") for record in records}:
        raise RuntimeError("baseline result request matrix mismatch")
    ordered = []
    for record in records:
        call = baseline_by_request[record["requestId"]]
        if (
            call.get("queryId") != record.get("queryId")
            or call.get("repeatIndex") != record.get("repeatIndex")
            or call.get("actualApplicationPredicate", {}).get("variant")
            != "bidirectional-current"
            or call.get("originalSearch", {}).get("variant")
            != "whole-keyword-plus-most-specific-current-published-menu-name"
        ):
            raise RuntimeError("baseline result provenance mismatch")
        ordered.append(call)
    return ordered


def structured_reanalyze(root: Path) -> None:
    """Compare A/B/C structured retrieval using an existing paid checkpoint."""
    metadata_path = root / "run-metadata.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    checkpoint_path = root / "calls.canonical.checkpoint.jsonl"
    _validate_source_checkpoint_sha256(metadata, checkpoint_path)
    if subprocess.run(
        ["git", "merge-base", "--is-ancestor", STRUCTURED_EVAL_BASE_SHA, "HEAD"],
        cwd=_repo_root(), check=False,
    ).returncode != 0:
        raise RuntimeError("structured evaluation is not based on approved PR #635 HEAD")
    dataset = _dataset()
    checkpoint = CheckpointStore(checkpoint_path)
    records = checkpoint.records()
    _validate_reanalysis_provenance(metadata, dataset, records)
    validate_checkpoint_matrix(
        queries=dataset["queries"], records=records, repeats=range(5),
    )
    baseline_calls = _load_validated_structured_baseline(root, records)
    query_by_id = {query["id"]: query for query in dataset["queries"]}
    structured_catalog = prepare_structured_catalog(
        families=dataset["families"], stores=dataset["stores"],
        menus=dataset["menus"],
    )
    structured_cache: dict[tuple[Any, ...], Any] = {}
    structured_calls = [
        evaluate_structured_variants(
            dataset=dataset,
            query=query_by_id[record["queryId"]],
            record=record,
            baseline_call=baseline_call,
            prepared_catalog=structured_catalog,
            retrieval_cache=structured_cache,
        )
        for record, baseline_call in zip(records, baseline_calls, strict=True)
    ]
    aggregate = aggregate_structured_comparison(structured_calls)
    output_root = root / "structured-reanalysis"
    result_path = write_jsonl(output_root / "results.jsonl", structured_calls)
    aggregate_path = write_json(output_root / "aggregate.json", aggregate)
    from hashlib import sha256
    analysis_sources = {
        "cli": Path(__file__),
        "structuredSearch": Path(__file__).with_name("structured_search.py"),
        "matching": Path(__file__).with_name("matching.py"),
        "evaluation": Path(__file__).with_name("evaluation.py"),
    }
    metadata["structuredReanalysis"] = {
        "schemaVersion": "miriyum-structured-search-reanalysis-v1",
        "status": "simulated-evidence-not-actual-application",
        "stackedOnPullRequest": 635,
        "stackedBaseCommitSha": STRUCTURED_EVAL_BASE_SHA,
        "analysisCommitSha": _commit_sha(),
        "analysisWorkingTreeDirty": subprocess.run(
            ["git", "diff", "--quiet"], cwd=_repo_root(), check=False,
        ).returncode != 0,
        "newProviderCalls": 0,
        "incrementalCostUsd": 0.0,
        "sourceCheckpointSha256": metadata["sourceCheckpointSha256"],
        "sourceBaselineResultsSha256": sha256(
            (root / "reanalysis" / ISSUE_616_MOST_SPECIFIC / "results.jsonl").read_bytes()
        ).hexdigest(),
        "analysisSourceSha256": {
            name: sha256(path.read_bytes()).hexdigest()
            for name, path in analysis_sources.items()
        },
        "gate": aggregate["gate"],
    }
    write_json(metadata_path, metadata)
    write_sha256_manifest(
        (result_path, aggregate_path), output_root / "sha256.json",
    )


def _validate_hybrid_source_files(root: Path, source: Path) -> dict[str, str]:
    from hashlib import sha256

    structured_path = root / "structured-reanalysis" / "results.jsonl"
    embedding_path = (
        source / "embeddings" / "text-embedding-3-large"
        / "embedding-checkpoint.jsonl"
    )
    if not structured_path.is_file():
        raise RuntimeError("structured results are missing")
    if not embedding_path.is_file():
        raise RuntimeError("large embedding checkpoint is missing")
    structured_sha = sha256(structured_path.read_bytes()).hexdigest()
    embedding_sha = sha256(embedding_path.read_bytes()).hexdigest()
    if structured_sha != HYBRID_STRUCTURED_RESULTS_SHA:
        raise RuntimeError("structured results SHA-256 mismatch")
    if embedding_sha != HYBRID_EMBEDDING_CHECKPOINT_SHA:
        raise RuntimeError("embedding checkpoint SHA-256 mismatch")
    return {
        "structuredResultsSha256": structured_sha,
        "embeddingCheckpointSha256": embedding_sha,
    }


def _load_validated_hybrid_structured_calls(
    root: Path, dataset: dict[str, Any],
) -> list[dict[str, Any]]:
    path = root / "structured-reanalysis" / "results.jsonl"
    by_key: dict[tuple[str, int], dict[str, Any]] = {}
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            call = json.loads(line)
            key = (call.get("queryId"), call.get("repeatIndex"))
            if (
                call.get("schemaVersion") != "miriyum-structured-search-call-v1"
                or not isinstance(key[0], str)
                or not isinstance(key[1], int)
                or key in by_key
                or "C" not in call.get("variants", {})
            ):
                raise RuntimeError(
                    f"structured result matrix/provenance mismatch at line {line_number}"
                )
            by_key[key] = call
    expected = {
        (query["id"], repeat_index)
        for query in dataset["queries"]
        for repeat_index in range(5)
    }
    if set(by_key) != expected:
        raise RuntimeError("structured result matrix must contain exactly 2,000 x 5 calls")
    return [
        by_key[(query["id"], repeat_index)]
        for query in dataset["queries"]
        for repeat_index in range(5)
    ]


def _hybrid_actual_metadata(runtime_git_sha: str) -> dict[str, Any]:
    return {
        "status": "actual-H-with-simulated-D-through-G",
        "variant": "H_ACTUAL_FOOD_EVIDENCE_V1",
        "label": "actual-application-predicate-food-evidence-v1",
        "actualApplication": True,
        "productionCommitSha": runtime_git_sha,
        "runtimeGitSha": runtime_git_sha,
        "analysisCommitSha": runtime_git_sha,
        "newProviderCalls": 0,
        "newEmbeddingCalls": 0,
    }


def hybrid_reanalyze(root: Path, source: Path) -> None:
    """Compare simulated D/E/F/G and actual-application H without paid calls."""
    from hashlib import sha256
    from .embeddings import embed_texts, topk_cosine

    source_hashes = _validate_hybrid_source_files(root, source)
    metadata_path = root / "run-metadata.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    dataset = _dataset()
    if metadata.get("datasetSha256") != dataset["metadata"]["datasetSha256"]:
        raise RuntimeError("hybrid reanalysis dataset provenance mismatch")
    structured_calls = _load_validated_hybrid_structured_calls(root, dataset)

    menu_ids = [menu["id"] for menu in dataset["menus"]]
    menu_texts = [
        f"{menu['name']} {menu['description']} {' '.join(menu['tags'])}"
        for menu in dataset["menus"]
    ]
    query_ids = [query["id"] for query in dataset["queries"]]
    query_texts = [query["text"] for query in dataset["queries"]]

    class NoProviderCallTransport:
        def __call__(self, model, texts, timeout_seconds):
            raise RuntimeError("frozen embedding cache is incomplete; provider calls are forbidden")

    embedding_root = source / "embeddings"
    corpus = embed_texts(
        ids=menu_ids, texts=menu_texts, model="text-embedding-3-large",
        artifact_dir=embedding_root, transport=NoProviderCallTransport(),
    )
    query_embeddings = embed_texts(
        ids=query_ids, texts=query_texts, model="text-embedding-3-large",
        artifact_dir=embedding_root, transport=NoProviderCallTransport(),
    )
    if (
        corpus.ids != tuple(menu_ids)
        or query_embeddings.ids != tuple(query_ids)
        or corpus.vectors.shape != (5_000, 3_072)
        or query_embeddings.vectors.shape != (2_000, 3_072)
        or corpus.requested_model != "text-embedding-3-large"
        or query_embeddings.requested_model != "text-embedding-3-large"
    ):
        raise RuntimeError("frozen embedding identities, model, or dimensions mismatch")
    indices, scores = topk_cosine(
        query_embeddings.vectors, corpus.vectors, k=200,
    )
    embedding_by_query = {
        query_id: tuple(
            (menu_ids[int(index)], float(score))
            for index, score in zip(row_indices, row_scores)
        )
        for query_id, row_indices, row_scores in zip(
            query_ids, indices, scores,
        )
    }
    query_by_id = {query["id"]: query for query in dataset["queries"]}
    catalog = prepare_hybrid_catalog(
        families=dataset["families"], stores=dataset["stores"],
        menus=dataset["menus"],
    )
    hybrid_calls = [
        evaluate_hybrid_variants(
            dataset=dataset, query=query_by_id[call["queryId"]],
            structured_call=call,
            embedding_menu_scores=embedding_by_query[call["queryId"]],
            prepared_catalog=catalog,
        )
        for call in structured_calls
    ]
    aggregate = aggregate_hybrid_comparison(hybrid_calls)

    output_root = root / "hybrid-reanalysis"
    result_path = write_jsonl(output_root / "results.jsonl", hybrid_calls)
    aggregate_path = write_json(output_root / "aggregate.json", aggregate)
    analysis_sources = {
        "cli": Path(__file__),
        "hybridSearch": Path(__file__).with_name("hybrid_search.py"),
        "structuredSearch": Path(__file__).with_name("structured_search.py"),
        "embeddings": Path(__file__).with_name("embeddings.py"),
        "matching": Path(__file__).with_name("matching.py"),
        "productionFoodVocabulary": _repo_root() / "backend/src/main/java/com/miriyum/domain/search/interpreter/FoodEvidenceVocabulary.java",
        "productionFoodExtractor": _repo_root() / "backend/src/main/java/com/miriyum/domain/search/interpreter/DeterministicFoodEvidenceExtractor.java",
        "productionSearchPredicate": _repo_root() / "backend/src/main/java/com/miriyum/domain/search/repository/IntegratedStoreSearchPredicates.java",
    }
    runtime_git_sha = _commit_sha()
    metadata["hybridReanalysis"] = {
        "schemaVersion": "miriyum-hybrid-search-reanalysis-v1",
        **_hybrid_actual_metadata(runtime_git_sha),
        "analysisWorkingTreeDirty": subprocess.run(
            ["git", "diff", "--quiet"], cwd=_repo_root(), check=False,
        ).returncode != 0,
        "incrementalCostUsd": 0.0,
        **source_hashes,
        "embeddingSourceArtifact": str(source.resolve()),
        "embeddingSourceDatasetSha256": json.loads(
            (source / "run-metadata.json").read_text(encoding="utf-8")
        ).get("datasetSha256"),
        "currentDatasetSha256": dataset["metadata"]["datasetSha256"],
        "requestedEmbeddingModel": "text-embedding-3-large",
        "returnedEmbeddingModels": sorted(set(
            corpus.returned_models + query_embeddings.returned_models
        )),
        "embeddingTopK": 200,
        "similarityImplementation": "numpy-matmul-argpartition",
        "analysisSourceSha256": {
            name: sha256(path.read_bytes()).hexdigest()
            for name, path in analysis_sources.items()
        },
        "targets": aggregate["targets"],
        "gate": aggregate["gate"],
    }
    write_json(metadata_path, metadata)
    write_sha256_manifest(
        (result_path, aggregate_path), output_root / "sha256.json",
    )


def _validate_reanalysis_provenance(
    metadata: dict[str, Any], dataset: dict[str, Any],
    records: list[dict[str, Any]],
) -> None:
    dataset_sha = dataset["metadata"]["datasetSha256"]
    if metadata.get("datasetSha256") != dataset_sha:
        raise RuntimeError("reanalysis dataset provenance mismatch")
    request_fingerprint = metadata.get("requestFingerprint")
    if not isinstance(request_fingerprint, str) or not request_fingerprint:
        raise RuntimeError("reanalysis request fingerprint is missing")
    for record in records:
        if record.get("requestFingerprint") != request_fingerprint:
            raise RuntimeError("reanalysis request fingerprint mismatch")
        query_id = record.get("queryId")
        repeat_index = record.get("repeatIndex")
        if not isinstance(query_id, str) or not isinstance(repeat_index, int):
            raise RuntimeError("reanalysis request id inputs are invalid")
        expected_request_id = deterministic_request_id_from_fingerprint(
            dataset_sha, query_id, repeat_index, request_fingerprint,
        )
        if record.get("requestId") != expected_request_id:
            raise RuntimeError("reanalysis deterministic request id mismatch")


def _checkpoint_sha256(checkpoint_path: Path) -> str:
    from hashlib import sha256

    if not checkpoint_path.is_file():
        raise RuntimeError("canonical checkpoint is missing")
    return sha256(checkpoint_path.read_bytes()).hexdigest()


def _pin_source_checkpoint_sha256(
    metadata: dict[str, Any], checkpoint_path: Path,
) -> None:
    current = _checkpoint_sha256(checkpoint_path)
    recorded = metadata.get("sourceCheckpointSha256")
    if recorded is not None and recorded != current:
        raise RuntimeError("canonical checkpoint SHA-256 is already pinned to different bytes")
    metadata["sourceCheckpointSha256"] = current


def _validate_source_checkpoint_sha256(
    metadata: dict[str, Any], checkpoint_path: Path,
) -> None:
    recorded = metadata.get("sourceCheckpointSha256")
    if not isinstance(recorded, str) or len(recorded) != 64:
        raise RuntimeError("canonical checkpoint SHA-256 provenance is missing")
    if recorded != _checkpoint_sha256(checkpoint_path):
        raise RuntimeError("canonical checkpoint SHA-256 mismatch")


def _write_paired_reanalysis_outputs(
    *, root: Path,
    baseline_calls: list[dict[str, Any]], baseline_aggregate: dict[str, Any],
    comparison_calls: list[dict[str, Any]], comparison_aggregate: dict[str, Any],
    summary: dict[str, Any],
) -> None:
    paired_root = root / "reanalysis"
    for variant, calls, aggregate in (
        (PRE_ISSUE_616, baseline_calls, baseline_aggregate),
        (ISSUE_616_MOST_SPECIFIC, comparison_calls, comparison_aggregate),
    ):
        variant_root = paired_root / variant
        write_jsonl(variant_root / "results.jsonl", calls)
        write_json(variant_root / "aggregate.json", aggregate)
    write_json(paired_root / "paired-summary.json", summary)


def _validate_reanalysis_variant(
    metadata: dict[str, Any], predicate_variant: str,
) -> None:
    if predicate_variant not in PREDICATE_VARIANTS:
        raise ValueError(f"unknown predicate variant: {predicate_variant}")
    analysis_variant = metadata.get("analysisPredicateVariant")
    execution_variant = metadata.get("executionPredicateVariant")
    if (
        analysis_variant is not None
        and execution_variant is not None
        and analysis_variant != execution_variant
    ):
        raise RuntimeError(
            "artifact predicate variant conflict: "
            f"analysis={analysis_variant}, execution={execution_variant}"
        )
    recorded = analysis_variant or execution_variant
    if recorded is not None and recorded != predicate_variant:
        raise RuntimeError(
            f"artifact predicate variant mismatch: recorded={recorded}, "
            f"requested={predicate_variant}"
        )


def stamp_reanalysis(
    root: Path,
    predicate_variant: str,
    *, paired_summary: dict[str, Any] | None = None,
    checkpoint_path: Path | None = None,
) -> None:
    metadata_path = root / "run-metadata.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    _validate_reanalysis_variant(metadata, predicate_variant)
    from hashlib import sha256
    analysis_sources = {
        "cli": Path(__file__),
        "matching": Path(__file__).with_name("matching.py"),
        "evaluation": Path(__file__).with_name("evaluation.py"),
        "query": _repo_root() / "backend/src/main/java/com/miriyum/domain/search/query/IntegratedStoreSearchQuery.java",
        "predicates": _repo_root() / "backend/src/main/java/com/miriyum/domain/search/repository/IntegratedStoreSearchPredicates.java",
        "repository": _repo_root() / "backend/src/main/java/com/miriyum/domain/search/repository/IntegratedStoreSearchRepository.java",
        "service": _repo_root() / "backend/src/main/java/com/miriyum/domain/search/service/IntegratedStoreSearchService.java",
        "menuNameIndexMigration": _repo_root() / "backend/src/main/resources/db/migration/V71__index_menu_version_name_for_search.sql",
    }
    metadata.update({
        "reanalyzedAtUtc": datetime.now(timezone.utc).isoformat(),
        "analysisCommitSha": _commit_sha(),
        "analysisWorkingTreeDirty": subprocess.run(
            ["git", "diff", "--quiet"], cwd=_repo_root(), check=False,
        ).returncode != 0,
        "analysisPredicateVariant": predicate_variant,
        "analysisPredicateVariants": list(PREDICATE_VARIANTS),
        "analysisPredicate": (
            "whole-keyword-plus-most-specific-current-published-menu-name-then-llm-bidirectional"
            if predicate_variant == ISSUE_616_MOST_SPECIFIC
            else "whole-keyword-then-llm-bidirectional-pre-issue-616"
        ),
        "analysisSourceSha256": {
            name: sha256(path.read_bytes()).hexdigest()
            for name, path in analysis_sources.items()
        },
    })
    if checkpoint_path is not None:
        _validate_source_checkpoint_sha256(metadata, checkpoint_path)
    if paired_summary is not None:
        metadata["pairedReanalysis"] = paired_summary
    write_json(metadata_path, metadata)


def report(root: Path) -> None:
    comparison_root = root / "reanalysis" / ISSUE_616_MOST_SPECIFIC
    aggregate_path = (
        comparison_root / "aggregate.json"
        if (comparison_root / "aggregate.json").exists()
        else root / "aggregate.json"
    )
    results_path = (
        comparison_root / "results.jsonl"
        if (comparison_root / "results.jsonl").exists()
        else root / "results.jsonl"
    )
    aggregate = json.loads(aggregate_path.read_text(encoding="utf-8"))
    gate = json.loads((root / "pilot-gate.json").read_text(encoding="utf-8"))
    metadata = json.loads((root / "run-metadata.json").read_text(encoding="utf-8"))
    raw_records = CheckpointStore(root / "calls.checkpoint.jsonl").records()
    canonical_records = CheckpointStore(root / "calls.canonical.checkpoint.jsonl").records()
    checkpoint_matrix = validate_checkpoint_matrix(
        queries=_dataset()["queries"], records=canonical_records, repeats=range(5),
    )
    write_json(root / "checkpoint-matrix.json", checkpoint_matrix)
    metadata["paidExecution"] = paid_execution_summary(
        raw_records, canonical_records,
        analyzed_calls=aggregate["statisticalUnit"]["calls"],
    )
    write_json(root / "run-metadata.json", metadata)
    embedding_path = root / "embedding-aggregate.json"
    embedding = json.loads(embedding_path.read_text(encoding="utf-8")) if embedding_path.exists() else None
    result_calls = [
        json.loads(line) for line in results_path.read_text(encoding="utf-8").splitlines()
        if line
    ]
    diagnostics = derive_call_diagnostics(result_calls)
    write_json(root / "derived-diagnostics.json", diagnostics)
    report_text = render_report(
        aggregate=aggregate, pilot_gate=gate, metadata=metadata,
        embedding_results=embedding, diagnostics=diagnostics,
    )
    (root / "report.md").write_text(report_text, encoding="utf-8")
    dataset_files = [
        root / name for name in (
            "manifest.jsonl", "metadata.json", "queries.jsonl", "validation.json",
        )
    ]
    write_sha256_manifest(dataset_files, root / "dataset-sha256.json")
    major_files = [path for path in root.rglob("*") if path.is_file() and path.name not in {"sha256.json"}]
    harness_root = Path(__file__).resolve().parents[2]
    repository_files = [
        path for path in harness_root.rglob("*")
        if path.is_file()
        and "artifacts" not in path.parts
        and "__pycache__" not in path.parts
        and path.suffix != ".pyc"
    ]
    documentation = _repo_root() / "docs/performance/search-evaluation-v2.md"
    if documentation.exists():
        repository_files.append(documentation)
    hashes = {}
    from hashlib import sha256
    for path in sorted(major_files + repository_files):
        label = (
            path.relative_to(root).as_posix()
            if path.is_relative_to(root)
            else f"repository/{path.relative_to(_repo_root()).as_posix()}"
        )
        hashes[label] = sha256(path.read_bytes()).hexdigest()
    write_json(root / "sha256.json", hashes)


def hash_artifact(root: Path) -> None:
    """Write hashes for every current artifact file without requiring a full report."""
    files = [
        path for path in root.rglob("*")
        if path.is_file() and path.name != "sha256.json"
    ]
    if not files:
        raise RuntimeError("artifact directory has no files to hash")
    write_sha256_manifest(files, root / "sha256.json")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="MiriYum reproducible search evaluation v2")
    parser.add_argument("command", choices=(
        "generate", "migrate-pilot", "migrate-checkpoint", "pilot", "run",
        "prompt-pilot", "prompt-run", "reanalyze", "structured-reanalyze",
        "hybrid-reanalyze",
        "stamp-reanalysis",
        "embeddings", "report", "hash-artifact", "model-compare",
    ))
    parser.add_argument("--artifact-dir", type=Path, required=True)
    parser.add_argument("--source-artifact-dir", type=Path)
    parser.add_argument("--predicate-variant", choices=PREDICATE_VARIANTS)
    args = parser.parse_args(argv)
    if args.command == "migrate-pilot":
        if args.source_artifact_dir is None:
            parser.error("migrate-pilot requires --source-artifact-dir")
        migrate_pilot(args.artifact_dir.resolve(), args.source_artifact_dir.resolve())
        return 0
    if args.command == "migrate-checkpoint":
        if args.source_artifact_dir is None:
            parser.error("migrate-checkpoint requires --source-artifact-dir")
        migrate_checkpoint(args.artifact_dir.resolve(), args.source_artifact_dir.resolve())
        return 0
    if args.command == "prompt-pilot":
        if args.source_artifact_dir is None:
            parser.error("prompt-pilot requires --source-artifact-dir")
        prompt_pilot(args.artifact_dir.resolve(), args.source_artifact_dir.resolve())
        return 0
    if args.command == "model-compare":
        if args.source_artifact_dir is None:
            parser.error("model-compare requires --source-artifact-dir")
        model_compare(args.artifact_dir.resolve(), args.source_artifact_dir.resolve())
        return 0
    if args.command in {"reanalyze", "stamp-reanalysis"}:
        if args.predicate_variant is None:
            parser.error(f"{args.command} requires --predicate-variant")
        action = reanalyze if args.command == "reanalyze" else stamp_reanalysis
        action(args.artifact_dir.resolve(), args.predicate_variant)
        return 0
    if args.command == "structured-reanalyze":
        structured_reanalyze(args.artifact_dir.resolve())
        return 0
    if args.command == "hybrid-reanalyze":
        if args.source_artifact_dir is None:
            parser.error("hybrid-reanalyze requires --source-artifact-dir")
        hybrid_reanalyze(
            args.artifact_dir.resolve(), args.source_artifact_dir.resolve(),
        )
        return 0
    actions = {
        "generate": generate, "pilot": pilot, "run": full_run,
        "prompt-run": prompt_full_run, "embeddings": embeddings, "report": report,
        "hash-artifact": hash_artifact,
    }
    actions[args.command](args.artifact_dir.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
