from pathlib import Path
from hashlib import sha256
import json
import subprocess
import tempfile
import unittest
from unittest import mock

from miriyum_search_eval.catalog import DatasetConfig, generate_dataset
from miriyum_search_eval.cli import (
    HYBRID_EMBEDDING_CHECKPOINT_SHA,
    HYBRID_STRUCTURED_RESULTS_SHA,
    ISSUE_616_MOST_SPECIFIC,
    PRE_ISSUE_616,
    _gpt54mini_comparison_config,
    _hybrid_actual_metadata,
    _git_paths_dirty,
    _load_validated_structured_baseline,
    _validate_hybrid_source_files,
    _pin_source_checkpoint_sha256,
    _validate_prompt_full_provenance,
    _validate_reanalysis_provenance,
    _validate_reanalysis_variant,
    _write_paired_reanalysis_outputs,
    generate,
    hash_artifact,
    reanalyze,
    structured_reanalyze,
)
from miriyum_search_eval.runner import CheckpointStore, EvalConfig, deterministic_request_id
from miriyum_search_eval.workflow import (
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


class WorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dataset = generate_dataset(DatasetConfig(20260823, "miriyum-search-eval-v2"))

    def test_pilot_is_deterministic_stratified_100(self):
        first = stratified_pilot_queries(self.dataset["queries"], seed=20260823)
        second = stratified_pilot_queries(self.dataset["queries"], seed=20260823)

        self.assertEqual(first, second)
        self.assertEqual(len(first), 100)
        counts = {}
        for query in first:
            counts[query["type"]] = counts.get(query["type"], 0) + 1
        self.assertEqual(counts, {
            "sensory_without_menu": 40, "composite_filter": 20,
            "alias_bidirectional": 15, "same_menu_ranking": 10,
            "negative_or_ambiguous_voice": 8, "filter_defense": 7,
        })
        voice = [query for query in first if query["type"] == "negative_or_ambiguous_voice"]
        self.assertEqual(
            {subtype: sum(query.get("subtype") == subtype for query in voice) for subtype in ("ambiguous_food", "true_no_answer")},
            {"ambiguous_food": 4, "true_no_answer": 4},
        )

    def test_dataset_hash_manifest_only_tracks_immutable_dataset_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            generate(root)
            hashes = json.loads((root / "dataset-sha256.json").read_text(encoding="utf-8"))

            self.assertNotIn("run-metadata.json", hashes)
            self.assertEqual(
                hashes,
                {
                    name: sha256((root / name).read_bytes()).hexdigest()
                    for name in ("manifest.jsonl", "metadata.json", "queries.jsonl", "validation.json")
                },
            )

    def test_partial_artifact_hashes_every_current_file_except_hash_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "nested").mkdir()
            (root / "calls.jsonl").write_text("one\n", encoding="utf-8")
            (root / "nested" / "gate.json").write_text("{}\n", encoding="utf-8")

            hash_artifact(root)
            hashes = json.loads((root / "sha256.json").read_text(encoding="utf-8"))

            self.assertEqual(set(hashes), {"calls.jsonl", "nested/gate.json"})
            self.assertNotIn("sha256.json", hashes)

    def test_pilot_gate_stops_on_format_or_projected_cost(self):
        calls = [{"providerFormatSuccess": True, "costUsd": 0.00005} for _ in range(100)]
        passed = pilot_gate(calls, projected_call_count=10_000, cost_cap_usd=1.5, resume_duplicate_calls=0)
        self.assertTrue(passed["passed"])

        calls[0]["providerFormatSuccess"] = False
        failed = pilot_gate(calls, projected_call_count=10_000, cost_cap_usd=0.4, resume_duplicate_calls=1)
        self.assertFalse(failed["passed"])
        self.assertIn("checkpoint_resume_duplicate", failed["fatalReasons"])
        self.assertIn("projected_cost_cap", failed["fatalReasons"])

    def test_pilot_gate_stops_when_abstention_still_has_severe_no_answer_false_positives(self):
        calls = [{
            "providerFormatSuccess": True, "costUsd": 0.00005,
            "querySubtype": "positive", "goldNegative": False,
            "semanticHit": True, "falsePositive": False,
        } for _ in range(96)]
        calls.extend({
            "providerFormatSuccess": True, "costUsd": 0.00005,
            "querySubtype": "true_no_answer", "goldNegative": True,
            "semanticHit": False, "falsePositive": index < 2,
        } for index in range(4))

        gate = pilot_gate(calls, projected_call_count=10_000, cost_cap_usd=1.5, resume_duplicate_calls=0)

        self.assertFalse(gate["passed"])
        self.assertIn("severe_no_answer_false_positive_rate", gate["fatalReasons"])
        self.assertEqual(gate["noAnswerFalsePositiveRate"], 0.5)

    def test_prompt_experiment_gate_requires_sensory_gain_without_safety_regression(self):
        baseline = []
        experiment = []
        for index in range(100):
            query_type = (
                "sensory_without_menu" if index < 40
                else "filter_defense" if index < 47
                else "negative_or_ambiguous_voice" if index < 55
                else "composite_filter"
            )
            gold_negative = 47 <= index < 51
            base_hit = index < 20 if query_type == "sensory_without_menu" else True
            experiment_hit = index < 24 if query_type == "sensory_without_menu" else True
            common = {
                "queryId": f"q-{index:03d}", "queryType": query_type,
                "goldNegative": gold_negative, "providerFormatSuccess": True,
                "closedLeak": False, "privateOrHistoricalLeak": False,
                "filterViolation": False,
            }
            baseline.append({
                **common,
                "simulatedEvidenceByCutoff": {
                    "@8": {"acceptableHit": base_hit, "strictFalsePositive": False},
                    "@20": {"acceptableHit": base_hit},
                },
            })
            experiment.append({
                **common,
                "simulatedEvidenceByCutoff": {
                    "@8": {"acceptableHit": experiment_hit, "strictFalsePositive": False},
                    "@20": {"acceptableHit": experiment_hit},
                },
            })

        passed = prompt_experiment_gate(baseline, experiment, minimum_sensory_gain=3)
        experiment[47]["simulatedEvidenceByCutoff"]["@8"]["strictFalsePositive"] = True
        failed = prompt_experiment_gate(baseline, experiment, minimum_sensory_gain=3)

        self.assertTrue(passed["passed"])
        self.assertEqual(passed["sensoryAcceptableAt20Gain"], 4)
        self.assertFalse(failed["passed"])
        self.assertIn("gold_negative_false_positive_regression", failed["fatalReasons"])

    def test_gpt54mini_comparison_keeps_prompt_but_uses_model_pricing(self):
        baseline = EvalConfig(system_instruction_override="same prompt")
        comparison = _gpt54mini_comparison_config("same prompt")

        self.assertEqual(comparison.model, "gpt-5.4-mini")
        self.assertEqual(comparison.system_instruction(), baseline.system_instruction())
        self.assertEqual(comparison.input_usd_per_million, 0.75)
        self.assertEqual(comparison.output_usd_per_million, 4.5)
        self.assertNotEqual(comparison.fingerprint(), baseline.fingerprint())

    def test_model_comparison_summary_uses_paired_unique_queries(self):
        baseline = []
        comparison = []
        for index in range(4):
            query_type = "sensory_without_menu" if index < 2 else "filter_defense"
            gold_negative = index == 3
            common = {
                "queryId": f"q-{index}", "queryType": query_type,
                "goldNegative": gold_negative, "providerFormatSuccess": True,
            }
            baseline.append({
                **common,
                "actualApplicationPredicate": {"hit": index == 0},
                "finalApplicationByCutoff": {
                    "@8": {"strictHit": index == 0, "acceptableHit": index < 2},
                },
                "simulatedEvidenceByCutoff": {
                    "@8": {"strictHit": index == 0, "acceptableHit": index == 0,
                           "strictFalsePositive": False},
                    "@20": {"acceptableHit": index == 0},
                },
            })
            comparison.append({
                **common,
                "actualApplicationPredicate": {"hit": index < 3},
                "finalApplicationByCutoff": {
                    "@8": {"strictHit": index < 2, "acceptableHit": index < 3},
                },
                "simulatedEvidenceByCutoff": {
                    "@8": {"strictHit": index < 2, "acceptableHit": index < 3,
                           "strictFalsePositive": False},
                    "@20": {"acceptableHit": index < 2},
                },
            })

        summary = model_comparison_summary(baseline, comparison)

        self.assertEqual(summary["pairedQueries"], 4)
        self.assertEqual(summary["actualApplicationPredicate"]["hitGain"], 2)
        self.assertEqual(summary["actualApplicationFinalSearch"]["strictAt8Gain"], 1)
        self.assertEqual(summary["actualApplicationFinalSearch"]["acceptableAt8Gain"], 1)
        self.assertEqual(summary["simulatedEvidence"]["strictAt8Gain"], 1)
        self.assertEqual(summary["simulatedEvidence"]["acceptableAt8Gain"], 2)
        self.assertEqual(summary["simulatedEvidence"]["sensoryAcceptableAt20Gain"], 1)
        self.assertEqual(summary["simulatedEvidence"]["goldNegativeFalsePositiveAt8Gain"], 0)

    def test_prompt_full_provenance_binds_gate_metadata_and_successful_pilot_records(self):
        config = EvalConfig(system_instruction_override="experiment")
        pilot_queries = [{"id": "q-1"}, {"id": "q-2"}]
        gate = {
            "datasetSha256": "dataset", "requestFingerprint": config.fingerprint(),
            "pilotQueryIds": ["q-1", "q-2"],
        }
        metadata = {"datasetSha256": "dataset", "requestFingerprint": config.fingerprint()}
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = CheckpointStore(Path(directory) / "calls.jsonl")
            for query in pilot_queries:
                checkpoint.append({
                    "requestId": deterministic_request_id("dataset", query["id"], 0, config),
                    "status": "success",
                })
            _validate_prompt_full_provenance(
                gate=gate, metadata=metadata, dataset_sha="dataset", config=config,
                pilot_queries=pilot_queries, checkpoint=checkpoint,
            )
            stale_gate = {**gate, "requestFingerprint": "stale"}
            with self.assertRaisesRegex(RuntimeError, "provenance"):
                _validate_prompt_full_provenance(
                    gate=stale_gate, metadata=metadata, dataset_sha="dataset", config=config,
                    pilot_queries=pilot_queries, checkpoint=checkpoint,
                )

    def test_migration_reuses_only_identical_query_text_and_config(self):
        config = EvalConfig()
        old_queries = [{"id": "same", "text": "같은 질의"}, {"id": "changed", "text": "이전 질의"}]
        new_queries = [{"id": "same", "text": "같은 질의"}, {"id": "changed", "text": "수정 질의"}]
        old_records = [
            {"requestId": "old-same", "queryId": "same", "repeatIndex": 0, "requestFingerprint": config.fingerprint(), "status": "success", "costUsd": 0.1},
            {"requestId": "old-changed", "queryId": "changed", "repeatIndex": 0, "requestFingerprint": config.fingerprint(), "status": "success", "costUsd": 0.1},
        ]
        with tempfile.TemporaryDirectory() as directory:
            store = CheckpointStore(Path(directory) / "new.jsonl")
            result = migrate_unchanged_calls(
                old_queries=old_queries, new_queries=new_queries, old_records=old_records,
                new_dataset_sha="new-sha", config=config, destination=store,
            )
            records = store.records()

        self.assertEqual(result, {"migrated": 1, "changedOrMissing": 1})
        self.assertEqual(records[0]["queryId"], "same")
        self.assertEqual(records[0]["migratedFromRequestId"], "old-same")
        self.assertEqual(records[0]["requestId"], deterministic_request_id("new-sha", "same", 0, config))

    def test_checkpoint_matrix_requires_every_query_repeat_exactly_once(self):
        queries = [{"id": "q1"}, {"id": "q2"}]
        valid = [
            {"queryId": query_id, "repeatIndex": repeat}
            for query_id in ("q1", "q2") for repeat in (0, 1)
        ]

        summary = validate_checkpoint_matrix(queries=queries, records=valid, repeats=range(2))

        self.assertEqual(summary, {"queries": 2, "repeats": 2, "records": 4})
        malformed = [*valid[:-1], {"queryId": "q1", "repeatIndex": 0}]
        with self.assertRaisesRegex(ValueError, "checkpoint matrix"):
            validate_checkpoint_matrix(queries=queries, records=malformed, repeats=range(2))

    def test_canonicalization_prefers_pilot_and_deduplicates_equivalent_paid_calls(self):
        pilot_config = EvalConfig(estimated_input_tokens=220, estimated_output_tokens=35)
        full_config = EvalConfig(estimated_input_tokens=339, estimated_output_tokens=46)
        queries = [{"id": "q1", "text": "얼큰한 국물"}, {"id": "q2", "text": "해물 면"}]
        records = [
            {"requestId": "pilot-q1", "queryId": "q1", "repeatIndex": 0, "requestFingerprint": pilot_config.fingerprint(), "concepts": ["pilot"]},
            {"requestId": "duplicate-q1", "queryId": "q1", "repeatIndex": 0, "requestFingerprint": full_config.fingerprint(), "concepts": ["duplicate"]},
            {"requestId": "full-q2", "queryId": "q2", "repeatIndex": 0, "requestFingerprint": full_config.fingerprint(), "concepts": ["full"]},
        ]
        with tempfile.TemporaryDirectory() as directory:
            destination = CheckpointStore(Path(directory) / "canonical.jsonl")
            result = canonicalize_equivalent_calls(
                queries=queries, records=records, dataset_sha="dataset-sha",
                source_configs=(pilot_config, full_config), destination_config=full_config,
                preferred_request_ids={"pilot-q1"}, destination=destination,
            )
            canonical = sorted(destination.records(), key=lambda record: record["queryId"])

        self.assertEqual(result["canonicalCalls"], 2)
        self.assertEqual(result["duplicateEquivalentCalls"], 1)
        self.assertEqual(canonical[0]["concepts"], ["pilot"])
        self.assertEqual(canonical[0]["requestFingerprint"], full_config.fingerprint())
        self.assertEqual(canonical[0]["requestId"], deterministic_request_id("dataset-sha", "q1", 0, full_config))

    def test_full_config_is_derived_only_from_the_frozen_100_call_pilot(self):
        pilot = [
            {"providerFormatSuccess": True, "inputTokens": 339, "outputTokens": 46}
            for _ in range(100)
        ]

        config = evaluation_config_from_pilot(pilot)

        self.assertEqual(config.estimated_input_tokens, 339)
        self.assertEqual(config.estimated_output_tokens, 46)

    def test_paid_execution_summary_counts_raw_ledger_and_only_new_canonical_calls(self):
        raw = [{"costUsd": 0.1}, {"costUsd": 0.2}]
        canonical = [
            {"costUsd": 0.1, "canonicalizedFromRequestId": "raw-1"},
            {"costUsd": 0.3},
        ]

        summary = paid_execution_summary(raw, canonical, analyzed_calls=2)

        self.assertEqual(summary["paidProviderCalls"], 3)
        self.assertEqual(summary["duplicateOverheadCalls"], 1)
        self.assertAlmostEqual(summary["actualPaidCostUsd"], 0.6)

    def test_paid_execution_summary_does_not_rebill_migrated_checkpoint_calls(self):
        canonical = [{
            "costUsd": 0.1,
            "migratedFromRequestId": "source-request",
        }]

        summary = paid_execution_summary([], canonical, analyzed_calls=1)

        self.assertEqual(summary["paidProviderCalls"], 0)
        self.assertEqual(summary["duplicateOverheadCalls"], 0)
        self.assertEqual(summary["actualPaidCostUsd"], 0.0)

    def test_reanalysis_rejects_recorded_predicate_variant_mismatch(self):
        metadata = {"analysisPredicateVariant": PRE_ISSUE_616}

        with self.assertRaisesRegex(RuntimeError, "predicate variant mismatch"):
            _validate_reanalysis_variant(metadata, ISSUE_616_MOST_SPECIFIC)

    def test_reanalysis_provenance_binds_dataset_fingerprint_and_request_id(self):
        config = EvalConfig()
        dataset = {
            "metadata": {"datasetSha256": "dataset-sha"},
            "queries": [{"id": "q1"}],
        }
        record = {
            "queryId": "q1",
            "repeatIndex": 0,
            "requestFingerprint": config.fingerprint(),
            "requestId": deterministic_request_id("dataset-sha", "q1", 0, config),
        }
        metadata = {
            "datasetSha256": "dataset-sha",
            "requestFingerprint": config.fingerprint(),
        }

        _validate_reanalysis_provenance(metadata, dataset, [record])

        for changed, message in (
            ({**metadata, "datasetSha256": "other"}, "dataset"),
            (metadata, "fingerprint"),
            (metadata, "request id"),
        ):
            invalid_record = dict(record)
            if message == "fingerprint":
                invalid_record["requestFingerprint"] = "other"
            if message == "request id":
                invalid_record["requestId"] = "mse2-other"
            with self.assertRaisesRegex(RuntimeError, message):
                _validate_reanalysis_provenance(changed, dataset, [invalid_record])

    def test_reanalysis_rejects_changed_canonical_checkpoint_before_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            checkpoint = root / "calls.canonical.checkpoint.jsonl"
            checkpoint.write_bytes(b"original-checkpoint\n")
            metadata_path = root / "run-metadata.json"
            metadata_path.write_text(json.dumps({
                "executionPredicateVariant": ISSUE_616_MOST_SPECIFIC,
                "sourceCheckpointSha256": sha256(checkpoint.read_bytes()).hexdigest(),
            }), encoding="utf-8")
            existing = root / "reanalysis" / "paired-summary.json"
            existing.parent.mkdir(parents=True)
            existing.write_bytes(b"existing-summary\n")
            before = {
                "metadata": metadata_path.read_bytes(),
                "summary": existing.read_bytes(),
            }

            checkpoint.write_bytes(b"changed-checkpoint\n")

            with self.assertRaisesRegex(RuntimeError, "checkpoint SHA-256 mismatch"):
                reanalyze(root, ISSUE_616_MOST_SPECIFIC)

            self.assertEqual(metadata_path.read_bytes(), before["metadata"])
            self.assertEqual(existing.read_bytes(), before["summary"])

    def test_structured_reanalysis_rejects_changed_checkpoint_before_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            checkpoint = root / "calls.canonical.checkpoint.jsonl"
            checkpoint.write_bytes(b"original-checkpoint\n")
            metadata = root / "run-metadata.json"
            metadata.write_text(json.dumps({
                "sourceCheckpointSha256": sha256(checkpoint.read_bytes()).hexdigest(),
            }), encoding="utf-8")
            existing = root / "structured-reanalysis" / "aggregate.json"
            existing.parent.mkdir(parents=True)
            existing.write_bytes(b"existing-aggregate\n")
            before = {
                "metadata": metadata.read_bytes(),
                "aggregate": existing.read_bytes(),
            }
            checkpoint.write_bytes(b"changed-checkpoint\n")

            with self.assertRaisesRegex(RuntimeError, "checkpoint SHA-256 mismatch"):
                structured_reanalyze(root)

            self.assertEqual(metadata.read_bytes(), before["metadata"])
            self.assertEqual(existing.read_bytes(), before["aggregate"])

    def test_structured_baseline_must_match_every_checkpoint_request(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            results = root / "reanalysis" / ISSUE_616_MOST_SPECIFIC / "results.jsonl"
            results.parent.mkdir(parents=True)
            baseline = {
                "requestId": "request-1",
                "queryId": "query-1",
                "repeatIndex": 0,
                "actualApplicationPredicate": {"variant": "bidirectional-current"},
                "originalSearch": {
                    "variant": "whole-keyword-plus-most-specific-current-published-menu-name",
                },
            }
            results.write_text(json.dumps(baseline) + "\n", encoding="utf-8")
            records = [{
                "requestId": "request-1",
                "queryId": "query-1",
                "repeatIndex": 0,
            }]

            self.assertEqual(
                _load_validated_structured_baseline(root, records),
                [baseline],
            )
            results.write_text(
                json.dumps({**baseline, "queryId": "changed-query"}) + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(RuntimeError, "baseline result provenance mismatch"):
                _load_validated_structured_baseline(root, records)

    def test_hybrid_source_files_require_frozen_structured_and_embedding_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "gold"
            source = Path(directory) / "embedding-source"
            structured = root / "structured-reanalysis" / "results.jsonl"
            embedding = (
                source / "embeddings" / "text-embedding-3-large"
                / "embedding-checkpoint.jsonl"
            )
            structured.parent.mkdir(parents=True)
            embedding.parent.mkdir(parents=True)
            structured.write_bytes(b"structured")
            embedding.write_bytes(b"embedding")

            with mock.patch(
                "miriyum_search_eval.cli.HYBRID_STRUCTURED_RESULTS_SHA",
                sha256(structured.read_bytes()).hexdigest(),
            ), mock.patch(
                "miriyum_search_eval.cli.HYBRID_EMBEDDING_CHECKPOINT_SHA",
                sha256(embedding.read_bytes()).hexdigest(),
            ):
                hashes = _validate_hybrid_source_files(root, source)

            self.assertEqual(
                hashes["structuredResultsSha256"],
                sha256(structured.read_bytes()).hexdigest(),
            )
            self.assertEqual(
                hashes["embeddingCheckpointSha256"],
                sha256(embedding.read_bytes()).hexdigest(),
            )
            structured.write_bytes(b"changed")
            with self.assertRaisesRegex(RuntimeError, "structured results SHA-256"):
                _validate_hybrid_source_files(root, source)

        self.assertEqual(len(HYBRID_STRUCTURED_RESULTS_SHA), 64)
        self.assertEqual(len(HYBRID_EMBEDDING_CHECKPOINT_SHA), 64)

    def test_hybrid_reanalysis_records_actual_h_without_new_paid_calls(self):
        metadata = _hybrid_actual_metadata("abc123")

        self.assertEqual(metadata["variant"], "H_ACTUAL_FOOD_EVIDENCE_V1")
        self.assertEqual(metadata["label"], "actual-application-predicate-food-evidence-v1")
        self.assertFalse(metadata["actualApplication"])
        self.assertTrue(metadata["actualApplicationPredicate"])
        self.assertEqual(metadata["queryEvidenceProvenance"], "legacy-structured-checkpoint-replay")
        self.assertEqual(metadata["productionCommitSha"], "abc123")
        self.assertEqual(metadata["runtimeGitSha"], "abc123")
        self.assertEqual(metadata["newProviderCalls"], 0)
        self.assertEqual(metadata["newEmbeddingCalls"], 0)

        dirty = _hybrid_actual_metadata("abc123", working_tree_dirty=True)
        self.assertIsNone(dirty["analysisCommitSha"])
        self.assertTrue(dirty["analysisWorkingTreeDirty"])

        production_dirty = _hybrid_actual_metadata(
            "abc123", production_working_tree_dirty=True,
        )
        self.assertIsNone(production_dirty["productionCommitSha"])
        self.assertEqual(production_dirty["productionBaseCommitSha"], "abc123")
        self.assertTrue(production_dirty["productionWorkingTreeDirty"])

    def test_git_paths_dirty_reads_porcelain_for_index_worktree_and_untracked(self):
        completed = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="?? new-file.py\n", stderr="",
        )
        with mock.patch("miriyum_search_eval.cli.subprocess.run", return_value=completed) as run:
            self.assertTrue(_git_paths_dirty(Path("new-file.py")))

        self.assertEqual(
            run.call_args.args[0],
            ["git", "status", "--porcelain", "--", "new-file.py"],
        )

    def test_canonical_checkpoint_sha_is_pinned_once(self):
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = Path(directory) / "calls.canonical.checkpoint.jsonl"
            checkpoint.write_bytes(b"canonical-checkpoint\n")
            metadata = {}

            _pin_source_checkpoint_sha256(metadata, checkpoint)

            self.assertEqual(
                metadata["sourceCheckpointSha256"],
                sha256(checkpoint.read_bytes()).hexdigest(),
            )
            checkpoint.write_bytes(b"changed-checkpoint\n")
            with self.assertRaisesRegex(RuntimeError, "already pinned"):
                _pin_source_checkpoint_sha256(metadata, checkpoint)

    def test_paired_reanalysis_requires_same_calls_and_no_true_no_answer_fp_gain(self):
        baseline = [{
            "queryId": "q1", "repeatIndex": 0,
            "querySubtype": "true_no_answer", "goldNegative": True,
            "falsePositive": False,
        }]
        improved = [{**baseline[0], "falsePositive": False}]

        summary = paired_reanalysis_summary(baseline, improved)

        self.assertEqual(summary["pairedCalls"], 1)
        self.assertEqual(summary["trueNoAnswerFalsePositiveGain"], 0)
        with self.assertRaisesRegex(RuntimeError, "true-no-answer"):
            paired_reanalysis_summary(
                baseline,
                [{**baseline[0], "falsePositive": True}],
            )
        with self.assertRaisesRegex(ValueError, "paired call keys"):
            paired_reanalysis_summary(
                baseline,
                [{**baseline[0], "queryId": "q2"}],
            )

    def test_paired_reanalysis_writes_separate_variant_outputs(self):
        baseline = [{"queryId": "q1", "repeatIndex": 0, "value": "before"}]
        comparison = [{"queryId": "q1", "repeatIndex": 0, "value": "after"}]
        baseline_aggregate = {"variant": PRE_ISSUE_616}
        comparison_aggregate = {"variant": ISSUE_616_MOST_SPECIFIC}
        summary = {"pairedCalls": 1, "trueNoAnswerFalsePositiveGain": 0}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_paired_reanalysis_outputs(
                root=root,
                baseline_calls=baseline,
                baseline_aggregate=baseline_aggregate,
                comparison_calls=comparison,
                comparison_aggregate=comparison_aggregate,
                summary=summary,
            )

            self.assertFalse((root / "results.jsonl").exists())
            self.assertEqual(
                json.loads((root / "reanalysis" / PRE_ISSUE_616 / "aggregate.json").read_text(encoding="utf-8")),
                baseline_aggregate,
            )
            self.assertEqual(
                json.loads((root / "reanalysis" / ISSUE_616_MOST_SPECIFIC / "aggregate.json").read_text(encoding="utf-8")),
                comparison_aggregate,
            )
            self.assertEqual(
                json.loads((root / "reanalysis" / "paired-summary.json").read_text(encoding="utf-8")),
                summary,
            )

    def test_reanalysis_mismatch_leaves_existing_artifact_bytes_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata = root / "run-metadata.json"
            results = root / "results.jsonl"
            aggregate = root / "aggregate.json"
            metadata.write_text(
                '{"executionPredicateVariant":"issue-616-most-specific"}\n',
                encoding="utf-8",
            )
            results.write_bytes(b"existing-results\n")
            aggregate.write_bytes(b"existing-aggregate\n")
            before = {
                path.name: path.read_bytes()
                for path in (metadata, results, aggregate)
            }

            with self.assertRaisesRegex(RuntimeError, "predicate variant mismatch"):
                reanalyze(root, PRE_ISSUE_616)

            self.assertEqual(
                before,
                {path.name: path.read_bytes() for path in (metadata, results, aggregate)},
            )


if __name__ == "__main__":
    unittest.main()
