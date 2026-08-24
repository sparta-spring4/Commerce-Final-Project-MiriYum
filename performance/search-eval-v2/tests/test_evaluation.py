import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from miriyum_search_eval.artifacts import write_dataset_artifacts, write_sha256_manifest
from miriyum_search_eval.catalog import DatasetConfig, generate_dataset
from miriyum_search_eval.evaluation import aggregate_evaluation, derive_call_diagnostics, evaluate_call
from miriyum_search_eval.matching import OriginalRetrievalResult, RetrievalResult, prepare_catalog


class EvaluationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dataset = generate_dataset(DatasetConfig(20260823, "miriyum-search-eval-v2.1"))

    @staticmethod
    def _record(query_id, concepts):
        return {
            "requestId": "req-metric", "queryId": query_id, "repeatIndex": 0,
            "status": "success", "interpretation": "MATCHABLE", "concepts": concepts,
            "latencyMs": 1.0, "inputTokens": 1, "outputTokens": 1, "costUsd": 0.0,
            "attempts": 1, "retryCount": 0, "requestedModel": "model", "returnedModel": "model",
        }

    def test_separates_attribute_understanding_strict_family_and_acceptable_family(self):
        family_by_id = {family["id"]: family for family in self.dataset["families"]}
        query = next(
            query for query in self.dataset["queries"]
            if query["type"] == "sensory_without_menu"
            and len(query["acceptableGold"]["familyIds"]) > 1
            and any(
                family_by_id[family_id]["baseName"]
                != family_by_id[query["gold"]["familyIds"][0]]["baseName"]
                for family_id in query["acceptableGold"]["familyIds"]
            )
        )
        strict_family = family_by_id[query["gold"]["familyIds"][0]]
        alternative = next(
            family_by_id[family_id] for family_id in query["acceptableGold"]["familyIds"]
            if family_by_id[family_id]["baseName"] != strict_family["baseName"]
        )

        attribute_only = evaluate_call(
            self.dataset, query,
            self._record(query["id"], [strict_family["attributes"]["ingredient"]]),
        )
        acceptable_family = evaluate_call(
            self.dataset, query, self._record(query["id"], [alternative["canonical"]]),
        )

        self.assertTrue(attribute_only["semanticHit"])
        self.assertFalse(attribute_only["strictFamilySemanticHit"])
        self.assertTrue(attribute_only["attributeUnderstanding"]["ingredient"])
        self.assertEqual(
            attribute_only["simulatedEvidenceSearch"]["variant"],
            "simulated-query-attribute-evidence-v1",
        )
        self.assertTrue(attribute_only["simulatedEvidenceByCutoff"]["@20"]["acceptableHit"])
        self.assertFalse(acceptable_family["strictFamilySemanticHit"])
        self.assertTrue(acceptable_family["acceptableFamilySemanticHit"])

    def test_final_application_reports_strict_and_acceptable_hits_at_all_cutoffs(self):
        query = dict(self.dataset["queries"][0])
        eligible_store_ids = [
            store["id"] for store in self.dataset["stores"]
            if store["verificationStatus"] == "APPROVED" and store["operationStatus"] != "CLOSED"
        ][:20]
        target = eligible_store_ids[11]
        query["gold"] = {**query["gold"], "storeIds": [target], "negative": False}
        query["acceptableGold"] = {
            **query["acceptableGold"], "storeIds": [target], "negative": False,
        }
        retrieval = RetrievalResult(
            one_way_menu_ids=(), bidirectional_menu_ids=(), alias_menu_ids=(),
            one_way_store_ids=tuple(eligible_store_ids),
            bidirectional_store_ids=tuple(eligible_store_ids),
            provenance={}, matched_modes={},
        )
        original = OriginalRetrievalResult((), ())

        with patch("miriyum_search_eval.evaluation.retrieve_candidates", return_value=retrieval), patch(
            "miriyum_search_eval.evaluation.retrieve_original_candidates", return_value=original,
        ):
            result = evaluate_call(self.dataset, query, self._record(query["id"], ["해물"]))

        self.assertFalse(result["finalApplication"]["hit"])
        self.assertFalse(result["finalApplicationByCutoff"]["@8"]["strictHit"])
        self.assertTrue(result["finalApplicationByCutoff"]["@20"]["strictHit"])
        self.assertTrue(result["finalApplicationByCutoff"]["@50"]["strictHit"])
        self.assertTrue(result["finalApplicationByCutoff"]["all"]["strictHit"])
        self.assertEqual(len(result["finalApplicationByCutoff"]["all"]["rankedStoreIds"]), 20)

    def test_evaluates_semantic_and_database_hits_separately(self):
        query = next(query for query in self.dataset["queries"] if query["type"] == "alias_bidirectional" and query["gold"].get("matchMode") == "reverse")
        gold_menu = next(
            menu for menu in self.dataset["menus"]
            if menu["storeId"] in query["gold"]["storeIds"]
            and menu["familyId"] in query["gold"]["familyIds"]
            and menu["visibility"] == "VISIBLE" and not menu["retired"]
        )
        record = {
            "requestId": "req-1", "queryId": query["id"], "repeatIndex": 0,
            "status": "success", "interpretation": "MATCHABLE",
            "concepts": [f"불향 해물 {gold_menu['name']}"],
            "latencyMs": 100.0, "inputTokens": 100, "outputTokens": 10,
            "costUsd": 0.000021, "attempts": 1, "retryCount": 0,
            "requestedModel": "gpt-4o-mini", "returnedModel": "gpt-4o-mini-2024-07-18",
        }

        result = evaluate_call(self.dataset, query, record)

        self.assertTrue(result["providerFormatSuccess"])
        self.assertEqual(result["schemaVersion"], "miriyum-search-call-evaluation-v2.1")
        self.assertTrue(result["semanticHit"])
        self.assertIn("oneWayDbCompatibleHit", result)
        self.assertIn("bidirectionalMenuNameHit", result)
        self.assertEqual(result["actualApplicationPredicate"]["variant"], "bidirectional-current")
        self.assertTrue(result["actualApplicationPredicate"]["hit"])
        self.assertEqual(result["legacyOneWayCounterfactual"]["variant"], "pre-issue-589-one-way")
        self.assertFalse(result["regionFilterViolation"])
        self.assertFalse(result["priceFilterViolation"])
        self.assertFalse(result["categoryFilterViolation"])
        self.assertIn("originalSearch", result)
        self.assertIn("supplementSearch", result)
        self.assertIn("finalApplication", result)
        self.assertEqual(
            result["finalApplication"]["hit"],
            result["originalSearch"]["hit"] or result["supplementSearch"]["hit"],
        )

    def test_repeated_identical_retrieval_inputs_share_one_cached_result(self):
        query = self.dataset["queries"][0]
        record = {
            "requestId": "req-cache", "queryId": query["id"], "repeatIndex": 0,
            "status": "success", "interpretation": "MATCHABLE", "concepts": ["얼큰한 국물"],
            "latencyMs": 1.0, "inputTokens": 1, "outputTokens": 1, "costUsd": 0.0,
            "attempts": 1, "retryCount": 0, "requestedModel": "model", "returnedModel": "model",
        }
        cache = {}
        prepared = prepare_catalog(
            families=self.dataset["families"], stores=self.dataset["stores"], menus=self.dataset["menus"],
        )

        first = evaluate_call(self.dataset, query, record, retrieval_cache=cache, prepared_catalog=prepared)
        second = evaluate_call(
            self.dataset, query, {**record, "repeatIndex": 1},
            retrieval_cache=cache, prepared_catalog=prepared,
        )

        self.assertEqual(len(cache), 3)
        self.assertEqual(first["rankedStoreIds"], second["rankedStoreIds"])

    def test_aggregate_uses_unique_queries_for_wilson_and_repeats_for_stability(self):
        query = self.dataset["queries"][0]
        calls = []
        for repeat in range(5):
            calls.append({
                "queryId": query["id"], "queryType": query["type"], "repeatIndex": repeat,
                "interpretation": "MATCHABLE",
                "providerFormatSuccess": True, "semanticHit": repeat < 3,
                "strictFamilySemanticHit": repeat < 2,
                "acceptableFamilySemanticHit": repeat < 3,
                "attributeUnderstanding": {
                    "ingredient": repeat < 3, "taste": repeat < 2,
                    "method": False, "broth": False, "aroma": False, "texture": False,
                },
                "oneWayDbCompatibleHit": repeat < 2, "bidirectionalMenuNameHit": repeat < 3,
                "actualApplicationPredicate": {"variant": "bidirectional-current", "hit": repeat < 3},
                "legacyOneWayCounterfactual": {"variant": "pre-issue-589-one-way", "hit": repeat < 2},
                "rankedStoreIds": ["s1", "s2"] if repeat != 4 else ["s2"],
                "finalApplicationByCutoff": {
                    "@8": {"strictHit": False, "acceptableHit": False, "strictRecall": 0.0, "acceptableRecall": 0.0},
                    "@20": {"strictHit": repeat < 3, "acceptableHit": repeat < 3, "strictRecall": 0.5, "acceptableRecall": 0.6},
                    "@50": {"strictHit": repeat < 3, "acceptableHit": True, "strictRecall": 0.7, "acceptableRecall": 0.8},
                    "all": {"strictHit": True, "acceptableHit": True, "strictRecall": 0.9, "acceptableRecall": 1.0},
                },
                "simulatedEvidenceByCutoff": {
                    "@8": {"strictHit": repeat < 3, "acceptableHit": True, "strictRecall": 0.4, "acceptableRecall": 0.5},
                    "@20": {"strictHit": True, "acceptableHit": True, "strictRecall": 0.6, "acceptableRecall": 0.7},
                    "@50": {"strictHit": True, "acceptableHit": True, "strictRecall": 0.8, "acceptableRecall": 0.9},
                    "all": {"strictHit": True, "acceptableHit": True, "strictRecall": 1.0, "acceptableRecall": 1.0},
                },
                "ranking": {"recall@1": 1.0, "recall@3": 1.0, "recall@5": 1.0, "recall@8": 1.0, "mrr": 1.0, "ndcg@1": 1.0, "ndcg@3": 1.0, "ndcg@5": 1.0, "ndcg@8": 1.0},
                "goldNegative": False,
                "falsePositive": False, "closedLeak": False, "privateOrHistoricalLeak": False,
                "filterViolation": False, "latencyMs": 100 + repeat,
                "inputTokens": 100, "outputTokens": 10, "costUsd": 0.000021,
                "attempts": 1, "retryCount": 0, "matchCounts": {"exact": 1, "forward": 0, "reverse": 0, "alias": 0},
                "falsePositiveCounts": {"exact": 0, "forward": 0, "reverse": 0, "alias": 0},
            })

        aggregate = aggregate_evaluation(calls)

        self.assertEqual(aggregate["statisticalUnit"]["uniqueQueries"], 1)
        self.assertEqual(aggregate["statisticalUnit"]["calls"], 5)
        self.assertEqual(aggregate["uniqueQuerySuccess"]["semantic"]["successes"], 1)
        self.assertEqual(aggregate["uniqueQuerySuccess"]["strictFamilySemantic"]["successes"], 0)
        self.assertEqual(aggregate["uniqueQuerySuccess"]["acceptableFamilySemantic"]["successes"], 1)
        self.assertEqual(aggregate["uniqueQuerySuccess"]["strictFinal@8"]["successes"], 0)
        self.assertEqual(aggregate["uniqueQuerySuccess"]["strictFinal@20"]["successes"], 1)
        self.assertEqual(aggregate["uniqueQuerySuccess"]["acceptableFinal@50"]["successes"], 1)
        self.assertEqual(aggregate["candidateCutoffs"]["@20"]["strictRecall"], 0.5)
        self.assertEqual(aggregate["uniqueQuerySuccess"]["simulatedStrictFinal@8"]["successes"], 1)
        self.assertEqual(aggregate["simulatedEvidenceCutoffs"]["@8"]["acceptableHitRate"], 1.0)
        self.assertEqual(aggregate["attributeUnderstanding"]["uniqueQuerySuccess"]["ingredient"]["successes"], 1)
        self.assertEqual(
            aggregate["queryTypeUniqueSuccess"]["sensory_without_menu"]["strictFinal@20"]["successes"],
            1,
        )
        self.assertEqual(aggregate["stability"]["queryCount"], 1)
        self.assertGreater(aggregate["stability"]["meanTop1Stability"], 0)

    def test_negative_false_positive_rate_only_uses_true_no_answer_gold(self):
        base = {
            "queryType": "negative_or_ambiguous_voice", "repeatIndex": 0,
            "interpretation": "NO_FOOD_SIGNAL",
            "providerFormatSuccess": True, "semanticHit": True,
            "oneWayDbCompatibleHit": True, "bidirectionalMenuNameHit": True,
            "actualApplicationPredicate": {"variant": "bidirectional-current", "hit": True},
            "legacyOneWayCounterfactual": {"variant": "pre-issue-589-one-way", "hit": True},
            "rankedStoreIds": [],
            "ranking": {"recall@1": 1.0, "recall@3": 1.0, "recall@5": 1.0, "recall@8": 1.0, "mrr": 1.0, "ndcg@1": 1.0, "ndcg@3": 1.0, "ndcg@5": 1.0, "ndcg@8": 1.0},
            "closedLeak": False, "privateOrHistoricalLeak": False, "filterViolation": False,
            "latencyMs": 1.0, "inputTokens": 1, "outputTokens": 1, "costUsd": 0.0,
            "attempts": 1, "retryCount": 0,
            "matchCounts": {"exact": 0, "forward": 0, "reverse": 0, "alias": 0},
            "falsePositiveCounts": {"exact": 0, "forward": 0, "reverse": 0, "alias": 0},
        }
        ambiguous = {**base, "queryId": "ambiguous", "goldNegative": False, "falsePositive": True}
        no_answer = {**base, "queryId": "no-answer", "goldNegative": True, "falsePositive": False}
        defense = {**base, "queryId": "defense", "queryType": "filter_defense", "goldNegative": True, "falsePositive": True}

        aggregate = aggregate_evaluation([ambiguous, no_answer, defense])

        self.assertEqual(aggregate["negativeFalsePositiveRate"], 0.0)
        self.assertEqual(aggregate["negativeFalsePositiveDenominator"], 1)

    def test_aggregate_reports_abstention_by_interpretation_and_subtype(self):
        base = {
            "queryType": "negative_or_ambiguous_voice", "repeatIndex": 0,
            "providerFormatSuccess": True, "semanticHit": True,
            "oneWayDbCompatibleHit": True, "bidirectionalMenuNameHit": True,
            "actualApplicationPredicate": {"variant": "bidirectional-current", "hit": True},
            "legacyOneWayCounterfactual": {"variant": "pre-issue-589-one-way", "hit": True},
            "rankedStoreIds": [], "goldNegative": True, "falsePositive": False,
            "ranking": {"recall@1": 1.0, "recall@3": 1.0, "recall@5": 1.0, "recall@8": 1.0, "mrr": 1.0, "ndcg@1": 1.0, "ndcg@3": 1.0, "ndcg@5": 1.0, "ndcg@8": 1.0},
            "closedLeak": False, "privateOrHistoricalLeak": False, "filterViolation": False,
            "latencyMs": 1.0, "inputTokens": 1, "outputTokens": 1, "costUsd": 0.0,
            "attempts": 1, "retryCount": 0,
            "matchCounts": {"exact": 0, "forward": 0, "reverse": 0, "alias": 0},
            "falsePositiveCounts": {"exact": 0, "forward": 0, "reverse": 0, "alias": 0},
        }
        no_signal = {**base, "queryId": "no-signal", "querySubtype": "true_no_answer", "interpretation": "NO_FOOD_SIGNAL"}
        ambiguous = {**base, "queryId": "ambiguous", "querySubtype": "ambiguous_food", "interpretation": "AMBIGUOUS"}

        aggregate = aggregate_evaluation([no_signal, ambiguous])

        self.assertEqual(aggregate["interpretations"], {"MATCHABLE": 0, "AMBIGUOUS": 1, "NO_FOOD_SIGNAL": 1})
        self.assertEqual(aggregate["abstentionRate"], 1.0)
        self.assertEqual(aggregate["interpretationBySubtype"]["true_no_answer"]["NO_FOOD_SIGNAL"], 1)

    def test_derived_diagnostics_count_unique_bidirectional_gain_and_answerable_abstention(self):
        rows = []
        for repeat in range(5):
            rows.append({
                "queryId": "q1", "repeatIndex": repeat, "goldNegative": False,
                "interpretation": "AMBIGUOUS" if repeat == 0 else "MATCHABLE",
                "oneWayDbCompatibleHit": False, "bidirectionalMenuNameHit": True,
                "querySubtype": None, "falsePositive": False,
            })

        diagnostics = derive_call_diagnostics(rows)

        self.assertEqual(diagnostics["uniqueBidirectionalGains"], 1)
        self.assertEqual(diagnostics["uniqueBidirectionalRegressions"], 0)
        self.assertEqual(diagnostics["answerableAbstentionRate"], 0.2)

    def test_diagnostics_only_count_abstention_when_final_search_misses(self):
        saved_by_original = {
            "queryId": "saved", "repeatIndex": 0, "goldNegative": False,
            "interpretation": "NO_FOOD_SIGNAL", "oneWayDbCompatibleHit": False,
            "bidirectionalMenuNameHit": False, "querySubtype": None,
            "falsePositive": False, "finalApplication": {"hit": True},
        }
        actual_miss = {
            **saved_by_original, "queryId": "miss", "finalApplication": {"hit": False},
        }

        diagnostics = derive_call_diagnostics([saved_by_original, actual_miss])

        self.assertEqual(diagnostics["answerableAbstentions"], 2)
        self.assertEqual(diagnostics["userVisibleAnswerableAbstentions"], 1)


class ArtifactTest(unittest.TestCase):
    def test_writes_reviewable_jsonl_and_sha256(self):
        dataset = generate_dataset(DatasetConfig(20260823, "miriyum-search-eval-v2"))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = write_dataset_artifacts(dataset, root)
            hashes = write_sha256_manifest(files, root / "sha256.json")
            manifest_lines = (root / "manifest.jsonl").read_text(encoding="utf-8").splitlines()

            self.assertEqual(len(manifest_lines), 1 + 300 + 500 + 5_000 + 2_000)
            self.assertEqual(json.loads(manifest_lines[0])["recordType"], "metadata")
            self.assertIn("manifest.jsonl", hashes)
            self.assertEqual(len(hashes["manifest.jsonl"]), 64)


if __name__ == "__main__":
    unittest.main()
