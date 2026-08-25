from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from miriyum_search_eval.staging_runner import (
    CALL_SCHEMA_VERSION,
    StagingCheckpoint,
    StagingEvalConfig,
    StagingTransportResponse,
    run_staging_requests,
    staging_request_id,
)


SHA_A = "a" * 40
SHA_B = "b" * 40
DATASET_SHA = "c" * 64
SYSTEM_SHA = "d" * 64
SCHEMA_SHA = "e" * 64


def config(**overrides):
    values = {
        "base_url": "https://staging-api.miriyum.click",
        "run_id": "staging-eval-20260825-01",
        "backend_sha": SHA_A,
        "harness_sha": SHA_B,
        "dataset_sha": DATASET_SHA,
        "requested_model": "gpt-4.1-mini",
        "temperature": 0.0,
        "system_instruction_sha": SYSTEM_SHA,
        "json_schema_sha": SCHEMA_SHA,
        "staging_approved": True,
        "harness_source_verified": True,
        "cloudwatch_evidence_verified": True,
        "request_interval_seconds": 0.0,
        "max_calls": 100,
        "max_http_attempts": 125,
        "backoff_base_seconds": 0.0,
    }
    values.update(overrides)
    return StagingEvalConfig(**values)


def success(store_ids=("8900001",)):
    return StagingTransportResponse(status=200, body={
        "code": "SUCCESS", "message": "ok", "data": {
            "items": [{"storeId": store_id} for store_id in store_ids],
            "normalizedCondition": {}, "warnings": [], "ruleVersion": "rule-v1",
            "vocabularyVersion": "catalog-v1+food-evidence-v1",
            "rankingRuleVersion": None, "nextCursor": None,
        },
    }, latency_ms=125.0)


class FakeTransport:
    def __init__(self, responses):
        self.responses = list(responses)
        self.queries = []

    def search(self, *, base_url, query_text, size, timeout_seconds):
        self.queries.append((base_url, query_text, size, timeout_seconds))
        response = self.responses.pop(0)
        if isinstance(response, BaseException):
            raise response
        return response


def result_records(checkpoint):
    return [record for record in checkpoint.records() if record.get("status") != "attempt_intent"]


class StagingRunnerTest(unittest.TestCase):
    def test_execution_gates_fail_before_network(self):
        transport = FakeTransport([success()])
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = StagingCheckpoint(Path(directory) / "calls.jsonl")

            with self.assertRaisesRegex(RuntimeError, "CloudWatch"):
                run_staging_requests(
                    [{"id": "q-1", "text": "질의"}], repeats=(0,),
                    config=config(cloudwatch_evidence_verified=False),
                    checkpoint=checkpoint, transport=transport,
                )

        self.assertEqual(transport.queries, [])

    def test_resume_reuses_success_and_keeps_public_ids_as_strings(self):
        queries = [{"id": "q-1", "text": "첫 질의"}, {"id": "q-2", "text": "둘째 질의"}]
        transport = FakeTransport([success(("8900002", "8900003"))])
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = StagingCheckpoint(Path(directory) / "calls.jsonl")
            request_id = staging_request_id(queries[0], 0, config())
            completed = {
                "schemaVersion": CALL_SCHEMA_VERSION,
                "requestId": request_id, "attemptId": f"{request_id}:1", "attempt": 1,
                "runId": config().run_id, "backendSha": SHA_A, "harnessSha": SHA_B,
                "datasetSha256": DATASET_SHA,
                "requestedModel": "gpt-4.1-mini", "temperature": 0.0,
                "systemInstructionSha256": SYSTEM_SHA, "jsonSchemaSha256": SCHEMA_SHA,
                "queryId": "q-1", "repeatIndex": 0, "status": "success",
                "providerHttpStatus": 200, "rankedStoreIds": ["8900001"],
            }
            checkpoint.append({**completed, "status": "attempt_intent"})
            checkpoint.append(completed)

            records = run_staging_requests(
                queries, repeats=(0,), config=config(), checkpoint=checkpoint,
                transport=transport,
            )

        self.assertEqual(len(transport.queries), 1)
        self.assertEqual(transport.queries[0][1], "둘째 질의")
        self.assertEqual([record["rankedStoreIds"] for record in records], [
            ["8900001"], ["8900002", "8900003"],
        ])

    def test_numeric_store_id_is_checkpointed_as_format_error(self):
        transport = FakeTransport([success((8900001,))])
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = StagingCheckpoint(Path(directory) / "calls.jsonl")

            records = run_staging_requests(
                [{"id": "q-1", "text": "질의"}], repeats=(0,), config=config(),
                checkpoint=checkpoint, transport=transport,
            )

        self.assertEqual(records[0]["status"], "format_error")
        self.assertEqual(records[0]["failureKind"], "invalid_public_id")
        self.assertNotIn("responseBody", records[0])

    def test_429_retries_with_distinct_attempt_ids_then_succeeds(self):
        transport = FakeTransport([
            StagingTransportResponse(status=429, body=None, latency_ms=20.0, retry_after_seconds=0.0),
            success(),
        ])
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = StagingCheckpoint(Path(directory) / "calls.jsonl")

            records = run_staging_requests(
                [{"id": "q-1", "text": "질의"}], repeats=(0,), config=config(),
                checkpoint=checkpoint, transport=transport,
            )
            attempts = result_records(checkpoint)

        self.assertEqual(records[0]["status"], "success")
        self.assertEqual([record["providerHttpStatus"] for record in attempts], [429, 200])
        self.assertEqual(len({record["attemptId"] for record in attempts}), 2)
        self.assertEqual(attempts[0]["requestId"], attempts[1]["requestId"])

    def test_timeout_does_not_reuse_previous_retry_after(self):
        transport = FakeTransport([
            StagingTransportResponse(status=429, body=None, latency_ms=20.0, retry_after_seconds=7.0),
            TimeoutError("timeout"),
            success(),
        ])
        sleeps = []
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = StagingCheckpoint(Path(directory) / "calls.jsonl")

            records = run_staging_requests(
                [{"id": "q-1", "text": "질의"}], repeats=(0,),
                config=config(backoff_base_seconds=2.0), checkpoint=checkpoint,
                transport=transport, sleep_fn=sleeps.append,
            )

        self.assertEqual(records[0]["status"], "success")
        self.assertEqual(sleeps, [7.0, 4.0])

    def test_checkpoint_with_different_provenance_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = StagingCheckpoint(Path(directory) / "calls.jsonl")
            checkpoint.append({
                "schemaVersion": CALL_SCHEMA_VERSION,
                "requestId": "f" * 64, "attemptId": f"{'f' * 64}:1", "attempt": 1,
                "runId": "different-run", "backendSha": SHA_A, "harnessSha": SHA_B,
                "datasetSha256": DATASET_SHA, "queryId": "q-1", "repeatIndex": 0,
                "requestedModel": "gpt-4.1-mini", "temperature": 0.0,
                "systemInstructionSha256": SYSTEM_SHA, "jsonSchemaSha256": SCHEMA_SHA,
                "status": "success", "providerHttpStatus": 200,
                "rankedStoreIds": ["8900001"],
            })

            with self.assertRaisesRegex(RuntimeError, "checkpoint provenance"):
                run_staging_requests(
                    [{"id": "q-1", "text": "질의"}], repeats=(0,), config=config(),
                    checkpoint=checkpoint, transport=FakeTransport([success()]),
                )

    def test_exhausted_retry_budget_is_terminal_across_resume(self):
        retryable = StagingTransportResponse(status=503, body=None, latency_ms=20.0)
        query = [{"id": "q-1", "text": "질의"}]
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = StagingCheckpoint(Path(directory) / "calls.jsonl")
            first_transport = FakeTransport([retryable, retryable])

            with self.assertRaisesRegex(RuntimeError, "circuit breaker"):
                run_staging_requests(
                    query, repeats=(0,), config=config(max_retries=1, circuit_breaker_failures=1),
                    checkpoint=checkpoint, transport=first_transport,
                )
            resumed_transport = FakeTransport([success()])
            with self.assertRaisesRegex(RuntimeError, "remains open"):
                run_staging_requests(
                    query, repeats=(0,), config=config(max_retries=1, circuit_breaker_failures=1),
                    checkpoint=checkpoint, transport=resumed_transport,
                )

            attempts = checkpoint.records()

        self.assertEqual(len(first_transport.queries), 2)
        self.assertEqual(resumed_transport.queries, [])
        self.assertEqual(attempts[-1]["status"], "provider_error_exhausted")

    def test_crash_after_intent_blocks_resume_without_second_network_call(self):
        query = [{"id": "q-1", "text": "질의"}]
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = StagingCheckpoint(Path(directory) / "calls.jsonl")
            crashing = FakeTransport([KeyboardInterrupt()])

            with self.assertRaises(KeyboardInterrupt):
                run_staging_requests(
                    query, repeats=(0,), config=config(), checkpoint=checkpoint,
                    transport=crashing,
                )
            resumed = FakeTransport([success()])
            with self.assertRaisesRegex(RuntimeError, "uncertain billing"):
                run_staging_requests(
                    query, repeats=(0,), config=config(), checkpoint=checkpoint,
                    transport=resumed,
                )

        self.assertEqual(len(crashing.queries), 1)
        self.assertEqual(resumed.queries, [])

    def test_out_of_corpus_store_id_is_rejected_without_persisting_identifier(self):
        transport = FakeTransport([success(("9999999",))])
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = StagingCheckpoint(Path(directory) / "calls.jsonl")

            records = run_staging_requests(
                [{"id": "q-1", "text": "질의"}], repeats=(0,), config=config(),
                checkpoint=checkpoint, transport=transport,
            )

            raw = checkpoint.path.read_text(encoding="utf-8")

        self.assertEqual(records[0]["failureKind"], "out_of_corpus_store_id")
        self.assertNotIn("9999999", raw)

    def test_expired_execution_window_blocks_before_network(self):
        transport = FakeTransport([success()])
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = StagingCheckpoint(Path(directory) / "calls.jsonl")

            with self.assertRaisesRegex(RuntimeError, "window is not active"):
                run_staging_requests(
                    [{"id": "q-1", "text": "질의"}], repeats=(0,),
                    config=config(approved_window_start_epoch=10.0, approved_window_end_epoch=20.0),
                    checkpoint=checkpoint, transport=transport, wall_clock_fn=lambda: 21.0,
                )

        self.assertEqual(transport.queries, [])

    def test_window_expiring_during_rate_limit_sleep_blocks_before_network(self):
        transport = FakeTransport([success(), success()])
        wall_times = iter((15.0, 15.0, 19.0, 21.0))
        monotonic_times = iter((0.0, 0.0, 0.0, 0.0))
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = StagingCheckpoint(Path(directory) / "calls.jsonl")

            with self.assertRaisesRegex(RuntimeError, "expired during pacing"):
                run_staging_requests(
                    [{"id": "q-1", "text": "첫 질의"}, {"id": "q-2", "text": "둘째 질의"}],
                    repeats=(0,),
                    config=config(request_interval_seconds=1.5, approved_window_start_epoch=10.0,
                                  approved_window_end_epoch=20.0),
                    checkpoint=checkpoint, transport=transport,
                    wall_clock_fn=lambda: next(wall_times),
                    clock_fn=lambda: next(monotonic_times), sleep_fn=lambda _: None,
                )

        self.assertEqual(len(transport.queries), 1)

    def test_request_identity_is_bound_to_environment_provenance(self):
        query = {"id": "q-1", "text": "질의"}

        baseline = staging_request_id(query, 0, config())

        self.assertEqual(baseline, staging_request_id(query, 0, config()))
        self.assertNotEqual(baseline, staging_request_id(query, 1, config()))
        self.assertNotEqual(baseline, staging_request_id(query, 0, config(backend_sha="d" * 40)))


if __name__ == "__main__":
    unittest.main()
