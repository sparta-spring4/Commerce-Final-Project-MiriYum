from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from miriyum_search_eval.staging_workflow import (
    _pilot_gate_evidence,
    generate_staging_artifacts,
    load_staging_dataset,
    staging_config_from_environment,
    validate_pilot_cloudwatch_proof,
    validate_staging_preflight,
    validate_pilot_transition,
    write_staging_report,
)
from miriyum_search_eval.staging_catalog import staging_pilot_queries
from miriyum_search_eval.staging_runner import (
    CALL_SCHEMA_VERSION, StagingCheckpoint, staging_request_id,
)


REPO_ROOT = Path(__file__).resolve().parents[3]
SEED_SQL = REPO_ROOT / "backend/scripts/dev-data/search-profile-demo-500-stores.sql"


class StagingWorkflowTest(unittest.TestCase):
    def test_generate_writes_valid_reproducible_manifest_and_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            generated = generate_staging_artifacts(root, seed_sql=SEED_SQL)
            loaded = load_staging_dataset(root)

            self.assertEqual(generated["metadata"]["datasetSha256"], loaded["metadata"]["datasetSha256"])
            self.assertEqual(len(loaded["queries"]), 2_000)
            self.assertEqual(json.loads((root / "validation.json").read_text(encoding="utf-8"))["errors"], [])
            hashes = json.loads((root / "dataset-sha256.json").read_text(encoding="utf-8"))
            self.assertIn("manifest.jsonl", hashes)
            self.assertIn("dataset.json", hashes)

    def test_load_rejects_dataset_bytes_changed_without_fingerprint_update(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            generate_staging_artifacts(root, seed_sql=SEED_SQL)
            dataset = json.loads((root / "dataset.json").read_text(encoding="utf-8"))
            dataset["queries"][0]["text"] += " 변조"
            (root / "dataset.json").write_text(json.dumps(dataset, ensure_ascii=False), encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "datasetFingerprintMismatch"):
                load_staging_dataset(root)

    def test_environment_config_requires_explicit_provenance_and_gates(self):
        environment = {
            "STAGING_EVAL_RUN_ID": "run-1",
            "STAGING_EVAL_BACKEND_SHA": "a" * 40,
            "STAGING_EVAL_HARNESS_SHA": "b" * 40,
            "STAGING_EVAL_APPROVED": "true",
            "STAGING_EVAL_HARNESS_VERIFIED": "true",
            "STAGING_EVAL_CLOUDWATCH_VERIFIED": "true",
            "STAGING_EVAL_REQUESTED_MODEL": "gpt-4.1-mini",
            "STAGING_EVAL_TEMPERATURE": "0",
            "STAGING_EVAL_SYSTEM_INSTRUCTION_SHA256": "d" * 64,
            "STAGING_EVAL_JSON_SCHEMA_SHA256": "e" * 64,
        }
        with patch.dict(os.environ, environment, clear=True):
            config = staging_config_from_environment("c" * 64, mode="pilot")

        self.assertEqual(config.max_calls, 100)
        self.assertEqual(config.max_http_attempts, 125)
        self.assertTrue(config.cloudwatch_evidence_verified)
        self.assertEqual(config.requested_model, "gpt-4.1-mini")

    def test_full_run_requires_matching_cloudwatch_pilot_proof(self):
        expected = {
            "runId": "run-1", "backendSha": "a" * 40,
            "harnessSha": "b" * 40, "datasetSha256": "c" * 64,
            "requestedModel": "gpt-4.1-mini", "temperature": 0.0,
            "systemInstructionSha256": "d" * 64, "jsonSchemaSha256": "e" * 64,
        }
        with tempfile.TemporaryDirectory() as directory:
            proof = Path(directory) / "cloudwatch-pilot-proof.json"
            proof.write_text(json.dumps({
                "schemaVersion": "miriyum-staging-cloudwatch-pilot-proof-v1",
                **expected, "verified": True, "observedLlmCalls": 100,
                "inputTokens": 1234, "outputTokens": 567, "actualCostUsd": 0.02,
                "returnedModels": ["gpt-4.1-mini-2025-04-14"],
                "meterWindowStartUtc": "2026-08-25T00:00:00Z",
                "meterWindowEndUtc": "2026-08-25T00:10:00Z",
                "meterNamespace": "MiriYum/Staging",
                "meterNames": [
                    "miriyum.search.llm.calls", "miriyum.search.llm.latency",
                    "miriyum.search.llm.outcomes", "miriyum.search.llm.tokens",
                ],
            }), encoding="utf-8")

            validated = validate_pilot_cloudwatch_proof(
                proof, expected=expected,
                execution_start=datetime(2026, 8, 25, 0, 1, tzinfo=timezone.utc),
                execution_end=datetime(2026, 8, 25, 0, 9, tzinfo=timezone.utc),
                validation_now=datetime(2026, 8, 25, 0, 11, tzinfo=timezone.utc),
            )

            zero_usage = json.loads(proof.read_text(encoding="utf-8"))
            zero_usage["actualCostUsd"] = 0.0
            proof.write_text(json.dumps(zero_usage), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "actualCostUsd"):
                validate_pilot_cloudwatch_proof(
                    proof, expected=expected,
                    execution_start=datetime(2026, 8, 25, 0, 1, tzinfo=timezone.utc),
                    execution_end=datetime(2026, 8, 25, 0, 9, tzinfo=timezone.utc),
                    validation_now=datetime(2026, 8, 25, 0, 11, tzinfo=timezone.utc),
                )
            zero_usage["actualCostUsd"] = 0.02
            zero_usage["meterWindowEndUtc"] = "2026-08-25T00:05:00Z"
            proof.write_text(json.dumps(zero_usage), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "actual pilot interval"):
                validate_pilot_cloudwatch_proof(
                    proof, expected=expected,
                    execution_start=datetime(2026, 8, 25, 0, 1, tzinfo=timezone.utc),
                    execution_end=datetime(2026, 8, 25, 0, 9, tzinfo=timezone.utc),
                    validation_now=datetime(2026, 8, 25, 0, 11, tzinfo=timezone.utc),
                )

        self.assertEqual(validated["observedLlmCalls"], 100)

    def test_preflight_binds_deployment_health_corpus_cloudwatch_and_window(self):
        environment = {
            "STAGING_EVAL_RUN_ID": "run-1",
            "STAGING_EVAL_BACKEND_SHA": "a" * 40,
            "STAGING_EVAL_HARNESS_SHA": "b" * 40,
            "STAGING_EVAL_APPROVED": "true",
            "STAGING_EVAL_HARNESS_VERIFIED": "true",
            "STAGING_EVAL_CLOUDWATCH_VERIFIED": "true",
            "STAGING_EVAL_REQUESTED_MODEL": "gpt-4.1-mini",
            "STAGING_EVAL_TEMPERATURE": "0",
            "STAGING_EVAL_SYSTEM_INSTRUCTION_SHA256": "d" * 64,
            "STAGING_EVAL_JSON_SCHEMA_SHA256": "e" * 64,
        }
        with patch.dict(os.environ, environment, clear=True):
            config = staging_config_from_environment("c" * 64, mode="pilot")
        dataset = {"metadata": {"corpusSourceSha256": "f" * 64}}
        proof_value = {
            "schemaVersion": "miriyum-staging-search-preflight-v1", "verified": True,
            "runId": "run-1", "backendSha": "a" * 40, "harnessSha": "b" * 40,
            "datasetSha256": "c" * 64, "requestedModel": "gpt-4.1-mini",
            "temperature": 0.0, "systemInstructionSha256": "d" * 64,
            "jsonSchemaSha256": "e" * 64,
            "deployment": {"host": "https://staging-api.miriyum.click", "backendSha": "a" * 40,
                           "checkedAtUtc": "2026-08-25T11:55:00Z"},
            "privateHealth": {"status": "UP", "checkedAtUtc": "2026-08-25T11:55:00Z"},
            "corpus": {"verified": True, "stores": 500, "menus": 5000, "sourceSha256": "f" * 64},
            "cloudwatch": {"readAccessVerified": True, "checkedAtUtc": "2026-08-25T11:55:00Z"},
            "approval": {"approved": True, "maxLogicalCalls": 10000, "maxCostUsd": 1.0},
            "rateLimitWindow": {"approved": True, "startsAtUtc": "2026-08-25T00:00:00Z",
                                "endsAtUtc": "2026-08-25T23:59:59Z", "maxRequestsPerMinute": 40},
        }
        with tempfile.TemporaryDirectory() as directory:
            proof = Path(directory) / "staging-preflight.json"
            proof.write_text(json.dumps(proof_value), encoding="utf-8")

            validated = validate_staging_preflight(
                proof, config=config, dataset=dataset,
                now=datetime(2026, 8, 25, 12, tzinfo=timezone.utc),
            )

        self.assertTrue(validated["cloudwatch"]["readAccessVerified"])

    def test_full_transition_requires_exact_current_100_pilot_request_ids(self):
        environment = {
            "STAGING_EVAL_RUN_ID": "run-1", "STAGING_EVAL_BACKEND_SHA": "a" * 40,
            "STAGING_EVAL_HARNESS_SHA": "b" * 40, "STAGING_EVAL_APPROVED": "true",
            "STAGING_EVAL_HARNESS_VERIFIED": "true", "STAGING_EVAL_CLOUDWATCH_VERIFIED": "true",
            "STAGING_EVAL_REQUESTED_MODEL": "gpt-4.1-mini", "STAGING_EVAL_TEMPERATURE": "0",
            "STAGING_EVAL_SYSTEM_INSTRUCTION_SHA256": "d" * 64,
            "STAGING_EVAL_JSON_SCHEMA_SHA256": "e" * 64,
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dataset = generate_staging_artifacts(root, seed_sql=SEED_SQL)
            with patch.dict(os.environ, environment, clear=True):
                config = staging_config_from_environment(dataset["metadata"]["datasetSha256"], mode="full")
            provenance = {
                "runId": config.run_id, "backendSha": config.backend_sha,
                "harnessSha": config.harness_sha, "datasetSha256": config.dataset_sha,
                "requestedModel": config.requested_model, "temperature": config.temperature,
                "systemInstructionSha256": config.system_instruction_sha,
                "jsonSchemaSha256": config.json_schema_sha,
            }
            gate = root / "staging-pilot-gate.json"
            checkpoint = StagingCheckpoint(root / "staging-calls.checkpoint.jsonl")
            for query in staging_pilot_queries(dataset["queries"], seed=dataset["metadata"]["seed"]):
                request_id = staging_request_id(query, 0, config)
                base = {
                    "schemaVersion": CALL_SCHEMA_VERSION, **provenance,
                    "requestId": request_id, "attemptId": f"{request_id}:1", "attempt": 1,
                    "queryId": query["id"], "queryType": query["queryType"], "repeatIndex": 0,
                }
                checkpoint.append({**base, "status": "attempt_intent"})
                checkpoint.append({**base, "status": "success", "providerHttpStatus": 200,
                                   "latencyMs": 100.0, "rankedStoreIds": query["goldStoreIds"][:1]})

            attempt_records = [
                record for record in checkpoint.records() if record.get("status") != "attempt_intent"
            ]
            evidence = _pilot_gate_evidence(dataset, attempt_records, attempt_records)
            gate.write_text(json.dumps({
                "schemaVersion": "miriyum-staging-search-pilot-gate-v1",
                **provenance, **evidence,
            }), encoding="utf-8")

            validate_pilot_transition(gate, checkpoint=checkpoint, config=config, dataset=dataset)
            stale = json.loads(gate.read_text(encoding="utf-8"))
            stale["backendSha"] = "9" * 40
            gate.write_text(json.dumps(stale), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "provenance"):
                validate_pilot_transition(gate, checkpoint=checkpoint, config=config, dataset=dataset)

    def test_report_states_black_box_limits_and_uses_repeat_zero_effectiveness(self):
        dataset = {
            "metadata": {"datasetSha256": "c" * 64},
            "stores": [{"id": "8900001", "region": "SEOUL", "categoryCode": "KOREAN"}],
            "menuTemplates": [], "menus": [],
            "queries": [{
                "id": "q-1", "queryType": "same_menu_ranking", "text": "질의",
                "goldStoreIds": ["8900001"], "expectedOrderedStoreIds": ["8900001"],
                "goldNegative": False, "filters": {},
            }],
        }
        records = [{
            "queryId": "q-1", "queryType": "same_menu_ranking", "repeatIndex": repeat,
            "status": "success", "failureKind": "", "providerHttpStatus": 200,
            "latencyMs": 100.0, "rankedStoreIds": ["8900001"],
        } for repeat in range(5)]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            aggregate = write_staging_report(root, dataset=dataset, terminal_records=records)
            report = (root / "report.md").read_text(encoding="utf-8")

        self.assertEqual(aggregate["effectivenessQueries"], 1)
        self.assertEqual(aggregate["analyzedCalls"], 5)
        self.assertIn("반복 0", report)
        self.assertIn("LLM 의미 해석 성공률은 관측할 수 없습니다", report)
        self.assertIn("81/90", report)
        self.assertIn("simulated 결과 없음", report)


if __name__ == "__main__":
    unittest.main()
