import json
from pathlib import Path
import tempfile
import unittest

from miriyum_search_eval.runner import (
    EvalConfig,
    CheckpointStore,
    build_request,
    deterministic_request_id,
    run_requests,
)


class FakeTransport:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def __call__(self, body, timeout_seconds):
        self.calls.append((body, timeout_seconds))
        return self.responses.pop(0)


class RunnerTest(unittest.TestCase):
    def test_retryable_unbilled_failures_are_audited_then_removed_for_resume(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            checkpoint = CheckpointStore(root / "calls.jsonl")
            checkpoint.append({
                "requestId": "success", "status": "success", "costUsd": 0.1,
                "providerHttpStatus": 200, "failureKind": "",
            })
            checkpoint.append({
                "requestId": "rate-limited", "status": "provider_error", "costUsd": 0.0,
                "providerHttpStatus": 429, "failureKind": "http_retryable",
            })

            result = checkpoint.quarantine_unbilled_retryable_failures(
                root / "retryable-failures.audit.jsonl",
            )

            self.assertEqual(result, {"quarantined": 1, "remaining": 1})
            self.assertEqual([record["requestId"] for record in checkpoint.records()], ["success"])
            audited = json.loads((root / "retryable-failures.audit.jsonl").read_text(encoding="utf-8"))
            self.assertEqual(audited["requestId"], "rate-limited")
            self.assertIsNone(checkpoint.get("rate-limited"))

    def test_quarantine_does_not_replay_5xx_timeout_or_unknown_billing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            checkpoint = CheckpointStore(root / "calls.jsonl")
            for record in (
                {"requestId": "server", "status": "provider_error", "costUsd": 0.0,
                 "providerHttpStatus": 503, "failureKind": "http_retryable"},
                {"requestId": "timeout", "status": "provider_error", "costUsd": 0.0,
                 "providerHttpStatus": 0, "failureKind": "timeout"},
                {"requestId": "unknown", "status": "provider_error",
                 "providerHttpStatus": 429, "failureKind": "http_retryable"},
            ):
                checkpoint.append(record)

            result = checkpoint.quarantine_unbilled_retryable_failures(root / "audit.jsonl")

            self.assertEqual(result, {"quarantined": 0, "remaining": 3})

    def setUp(self):
        self.config = EvalConfig(concurrency=1, max_retries=2, backoff_base_seconds=0)
        self.query = {"id": "query-0001", "text": "얼큰한 국물 음식 찾아줘"}
        self.success = (
            200,
            {"x-request-id": "provider-request"},
            {
                "id": "chatcmpl-test",
                "model": "gpt-4o-mini-2024-07-18",
                "choices": [{"finish_reason": "stop", "message": {"content": '{"interpretation":"MATCHABLE","concepts":["짬뽕"]}', "refusal": None}}],
                "usage": {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 110},
            },
        )

    def test_request_matches_application_contract_and_has_stable_id(self):
        first = deterministic_request_id("dataset-sha", "query-0001", 0, self.config)
        second = deterministic_request_id("dataset-sha", "query-0001", 0, self.config)
        request = build_request(self.query["text"], self.config)

        self.assertEqual(first, second)
        self.assertEqual(request["model"], "gpt-4o-mini")
        self.assertEqual(request["temperature"], 0.0)
        self.assertEqual(request["max_tokens"], 100)
        self.assertEqual(request["response_format"]["type"], "json_schema")
        self.assertTrue(request["response_format"]["json_schema"]["strict"])
        schema = request["response_format"]["json_schema"]["schema"]
        self.assertEqual(schema["required"], ["interpretation", "concepts"])
        self.assertEqual(
            schema["properties"]["interpretation"]["enum"],
            ["MATCHABLE", "AMBIGUOUS", "NO_FOOD_SIGNAL"],
        )
        self.assertEqual(request["messages"][0]["role"], "system")

    def test_prompt_override_gets_distinct_request_identity_without_changing_default_fingerprint(self):
        frozen_full_config = EvalConfig(estimated_input_tokens=339, estimated_output_tokens=46)
        experiment = EvalConfig(system_instruction_override="속성 증거를 모두 보존하세요")

        self.assertEqual(
            frozen_full_config.fingerprint(),
            "3d4d94d01fce927e7dc2024132bc931b0617565de2f11b87d54d7e5465a9296c",
        )
        self.assertNotEqual(experiment.fingerprint(), self.config.fingerprint())
        self.assertNotEqual(
            deterministic_request_id("dataset-sha", self.query["id"], 0, experiment),
            deterministic_request_id("dataset-sha", self.query["id"], 0, self.config),
        )
        self.assertEqual(
            build_request(self.query["text"], experiment)["messages"][0]["content"],
            "속성 증거를 모두 보존하세요",
        )

    def test_reasoning_model_uses_compatible_chat_completion_parameters(self):
        config = EvalConfig(model="gpt-5.4-mini", reasoning_effort="none")

        request = build_request(self.query["text"], config)

        self.assertEqual(request["reasoning_effort"], "none")
        self.assertEqual(request["max_completion_tokens"], 100)
        self.assertNotIn("max_tokens", request)
        self.assertEqual(request["temperature"], 0.0)

    def test_retries_429_then_checkpoints_success_without_secret(self):
        transport = FakeTransport([
            (429, {"retry-after": "0"}, {"error": {"message": "rate limited"}}),
            self.success,
        ])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "checkpoint.jsonl"
            store = CheckpointStore(path)
            results = run_requests(
                [self.query], repeats=(0,), dataset_sha="dataset-sha", config=self.config,
                checkpoint=store, transport=transport, sleep=lambda _: None,
            )
            raw = path.read_text(encoding="utf-8")

        self.assertEqual(len(transport.calls), 2)
        self.assertEqual(results[0]["status"], "success")
        self.assertEqual(results[0]["attempts"], 2)
        self.assertEqual(results[0]["interpretation"], "MATCHABLE")
        self.assertEqual(results[0]["concepts"], ["짬뽕"])
        self.assertEqual(results[0]["returnedModel"], "gpt-4o-mini-2024-07-18")
        self.assertNotIn("OPENAI_API_KEY", raw)
        self.assertNotIn("Authorization", raw)
        self.assertNotIn("rate limited", raw)

    def test_exhausted_429_trips_circuit_breaker_before_next_logical_call(self):
        config = EvalConfig(concurrency=1, max_retries=0, backoff_base_seconds=0)
        transport = FakeTransport([
            (429, {}, {"error": {"type": "rate_limit", "code": "rate_limit_exceeded"}}),
        ])
        queries = [self.query, {"id": "query-0002", "text": "두 번째 질의"}]
        with tempfile.TemporaryDirectory() as directory:
            store = CheckpointStore(Path(directory) / "checkpoint.jsonl")
            with self.assertRaisesRegex(RuntimeError, "429 circuit breaker"):
                run_requests(
                    queries, repeats=(0,), dataset_sha="dataset-sha", config=config,
                    checkpoint=store, transport=transport, sleep=lambda _: None,
                )
            records = store.records()

        self.assertEqual(len(transport.calls), 1)
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0]["providerHttpStatus"], 429)

    def test_format_error_still_accounts_provider_usage_and_cost(self):
        malformed = (
            200, {}, {
                "model": "gpt-4o-mini-2024-07-18",
                "choices": [{"finish_reason": "stop", "message": {"content": "not-json"}}],
                "usage": {"prompt_tokens": 100, "completion_tokens": 10},
            },
        )
        with tempfile.TemporaryDirectory() as directory:
            records = run_requests(
                [self.query], repeats=(0,), dataset_sha="dataset-sha", config=self.config,
                checkpoint=CheckpointStore(Path(directory) / "checkpoint.jsonl"),
                transport=FakeTransport([malformed]), sleep=lambda _: None,
            )

        self.assertEqual(records[0]["status"], "format_error")
        self.assertEqual(records[0]["inputTokens"], 100)
        self.assertEqual(records[0]["outputTokens"], 10)
        self.assertGreater(records[0]["costUsd"], 0)

    def test_non_matchable_interpretation_discards_concepts_like_application(self):
        ambiguous = (
            200,
            {},
            {
                "model": "gpt-4o-mini-2024-07-18",
                "choices": [{
                    "finish_reason": "stop",
                    "message": {
                        "content": '{"interpretation":"AMBIGUOUS","concepts":["국밥"]}',
                        "refusal": None,
                    },
                }],
                "usage": {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 110},
            },
        )
        with tempfile.TemporaryDirectory() as directory:
            results = run_requests(
                [self.query], repeats=(0,), dataset_sha="dataset-sha", config=self.config,
                checkpoint=CheckpointStore(Path(directory) / "checkpoint.jsonl"),
                transport=FakeTransport([ambiguous]), sleep=lambda _: None,
            )

        self.assertEqual(results[0]["status"], "success")
        self.assertEqual(results[0]["interpretation"], "AMBIGUOUS")
        self.assertEqual(results[0]["concepts"], [])

    def test_resume_does_not_repeat_a_paid_call(self):
        transport = FakeTransport([self.success])
        with tempfile.TemporaryDirectory() as directory:
            store = CheckpointStore(Path(directory) / "checkpoint.jsonl")
            first = run_requests(
                [self.query], repeats=(0,), dataset_sha="dataset-sha", config=self.config,
                checkpoint=store, transport=transport, sleep=lambda _: None,
            )
            second_transport = FakeTransport([])
            second = run_requests(
                [self.query], repeats=(0,), dataset_sha="dataset-sha", config=self.config,
                checkpoint=store, transport=second_transport, sleep=lambda _: None,
            )

        self.assertEqual(first, second)
        self.assertEqual(second_transport.calls, [])

    def test_call_cap_fails_before_network(self):
        config = EvalConfig(concurrency=1, max_calls=1)
        transport = FakeTransport([self.success])
        with tempfile.TemporaryDirectory() as directory:
            store = CheckpointStore(Path(directory) / "checkpoint.jsonl")
            with self.assertRaisesRegex(ValueError, "call cap"):
                run_requests(
                    [self.query], repeats=(0, 1), dataset_sha="dataset-sha", config=config,
                    checkpoint=store, transport=transport, sleep=lambda _: None,
                )
        self.assertEqual(transport.calls, [])

    def test_actual_cost_cap_stops_before_submitting_remaining_calls(self):
        expensive = (
            200, {}, {
                "model": "gpt-4o-mini-2024-07-18",
                "choices": [{"finish_reason": "stop", "message": {"content": '{"interpretation":"MATCHABLE","concepts":["짬뽕"]}', "refusal": None}}],
                "usage": {"prompt_tokens": 1_000_000, "completion_tokens": 1_000_000, "total_tokens": 2_000_000},
            },
        )
        config = EvalConfig(
            concurrency=1, max_calls=5, cost_cap_usd=0.5,
            estimated_input_tokens=0, estimated_output_tokens=0,
        )
        transport = FakeTransport([expensive] * 5)
        queries = [{"id": f"query-{index}", "text": "합성 질의"} for index in range(5)]
        with tempfile.TemporaryDirectory() as directory:
            store = CheckpointStore(Path(directory) / "checkpoint.jsonl")
            with self.assertRaisesRegex(RuntimeError, "actual cost cap"):
                run_requests(
                    queries, repeats=(0,), dataset_sha="dataset-sha", config=config,
                    checkpoint=store, transport=transport, sleep=lambda _: None,
                )
        self.assertEqual(len(transport.calls), 1)

    def test_cost_cap_drains_and_checkpoints_already_running_calls(self):
        expensive = (
            200, {}, {
                "model": "gpt-4o-mini-2024-07-18",
                "choices": [{"finish_reason": "stop", "message": {"content": '{"interpretation":"MATCHABLE","concepts":["짬뽕"]}', "refusal": None}}],
                "usage": {"prompt_tokens": 1_000_000, "completion_tokens": 1_000_000},
            },
        )
        config = EvalConfig(
            concurrency=2, max_calls=3, cost_cap_usd=0.5,
            estimated_input_tokens=0, estimated_output_tokens=0,
        )
        queries = [{"id": f"query-{index}", "text": "합성 질의"} for index in range(3)]
        transport = FakeTransport([expensive, expensive])
        with tempfile.TemporaryDirectory() as directory:
            store = CheckpointStore(Path(directory) / "checkpoint.jsonl")
            with self.assertRaisesRegex(RuntimeError, "actual cost cap"):
                run_requests(
                    queries, repeats=(0,), dataset_sha="dataset-sha", config=config,
                    checkpoint=store, transport=transport, sleep=lambda _: None,
                )
            records = store.records()

        self.assertEqual(len(transport.calls), 2)
        self.assertEqual(len(records), 2)

    def test_resume_at_cost_cap_stops_before_submitting_new_calls(self):
        config = EvalConfig(
            concurrency=4, max_calls=2, cost_cap_usd=0.5,
            estimated_input_tokens=0, estimated_output_tokens=0,
        )
        first_query = {"id": "query-1", "text": "첫 질의"}
        second_query = {"id": "query-2", "text": "둘째 질의"}
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = CheckpointStore(Path(directory) / "checkpoint.jsonl")
            checkpoint.append({
                "requestId": deterministic_request_id("dataset-sha", "query-1", 0, config),
                "status": "success", "costUsd": 0.5,
            })
            transport = FakeTransport([self.success])
            with self.assertRaisesRegex(RuntimeError, "already reached"):
                run_requests(
                    [first_query, second_query], repeats=(0,), dataset_sha="dataset-sha",
                    config=config, checkpoint=checkpoint, transport=transport,
                    sleep=lambda _: None,
                )

        self.assertEqual(transport.calls, [])


if __name__ == "__main__":
    unittest.main()
