from __future__ import annotations

import unittest

from miriyum_search_eval.staging_evaluation import (
    aggregate_staging_evaluation,
    evaluate_staging_records,
)


class StagingEvaluationTest(unittest.TestCase):
    def test_effectiveness_uses_repeat_zero_and_repeats_only_for_stability(self):
        dataset = {
            "stores": [
                {"id": "1", "region": "SEOUL", "categoryCode": "KOREAN"},
                {"id": "2", "region": "SEOUL", "categoryCode": "KOREAN"},
                {"id": "3", "region": "BUSAN", "categoryCode": "CHINESE"},
                {"id": "9", "region": "SEOUL", "categoryCode": "WESTERN"},
            ],
            "menuTemplates": [
                {"categoryCode": "KOREAN", "menuSlot": 1, "price": 9_000},
                {"categoryCode": "CHINESE", "menuSlot": 1, "price": 20_000},
            ],
            "menus": [
                {"storeId": "2", "categoryCode": "KOREAN", "menuSlot": 1},
                {"storeId": "3", "categoryCode": "CHINESE", "menuSlot": 1},
            ],
            "queries": [
                {"id": "q-1", "queryType": "sensory_without_menu", "goldStoreIds": ["1", "2"],
                 "expectedOrderedStoreIds": [], "goldNegative": False, "filters": {}},
                {"id": "q-2", "queryType": "negative_or_ambiguous", "goldStoreIds": [],
                 "expectedOrderedStoreIds": [], "goldNegative": True, "filters": {}},
                {"id": "q-3", "queryType": "composite_filter", "goldStoreIds": ["2"],
                 "expectedOrderedStoreIds": ["2"], "goldNegative": False,
                 "filters": {"region": "SEOUL", "categoryCode": "KOREAN", "maximumPrice": 10_000}},
            ],
        }
        records = [
            self._record("q-1", 0, ["1", "9", "2"], 100),
            self._record("q-1", 1, ["1", "2"], 110),
            self._record("q-2", 0, ["9"], 120),
            self._record("q-2", 1, ["9"], 130),
            self._record("q-3", 0, ["3"], 140),
            self._record("q-3", 1, ["2"], 150),
        ]

        evaluated = evaluate_staging_records(dataset, records)
        aggregate = aggregate_staging_evaluation(evaluated)

        primary = {row["queryId"]: row for row in evaluated if row["repeatIndex"] == 0}
        self.assertTrue(primary["q-1"]["ranking"]["hitAt1"])
        self.assertEqual(primary["q-1"]["ranking"]["recallAt3"], 1.0)
        self.assertEqual(primary["q-1"]["ranking"]["mrr"], 1.0)
        self.assertTrue(primary["q-2"]["falsePositive"])
        self.assertTrue(primary["q-3"]["regionFilterViolation"])
        self.assertTrue(primary["q-3"]["categoryFilterViolation"])
        self.assertTrue(primary["q-3"]["priceFilterViolation"])
        self.assertEqual(aggregate["effectivenessUnit"], "unique-query-repeat-0")
        self.assertEqual(aggregate["effectivenessQueries"], 3)
        self.assertEqual(aggregate["analyzedCalls"], 6)
        self.assertEqual(aggregate["ranking"]["hitsAt1"], 1)
        self.assertEqual(aggregate["ranking"]["queries"], 2)
        self.assertEqual(aggregate["ranking"]["expectedOrderQueries"], 1)
        self.assertEqual(aggregate["ranking"]["expectedOrderHits"], 0)
        self.assertEqual(aggregate["negativeFalsePositive"]["falsePositives"], 1)
        self.assertEqual(aggregate["negativeFalsePositive"]["queries"], 1)
        self.assertEqual(aggregate["filterViolations"]["price"], 1)
        self.assertEqual(aggregate["stability"]["queriesWithRepeats"], 0)
        self.assertEqual(aggregate["stability"]["top1StableQueries"], 0)
        self.assertEqual(aggregate["latencyMs"]["mean"], 125.0)
        self.assertEqual(aggregate["wilson95"]["positiveHitAt1"]["denominator"], 2)
        self.assertEqual(aggregate["wilson95"]["overallCorrectAt1"]["denominator"], 3)
        self.assertEqual(aggregate["wilson95"]["overallCorrectAt1"]["successes"], 1)

    def test_provider_and_format_failures_do_not_become_empty_successes(self):
        dataset = {
            "stores": [{"id": "1", "region": "SEOUL", "categoryCode": "KOREAN"}],
            "queries": [{"id": "q-1", "queryType": "sensory_without_menu", "goldStoreIds": ["1"],
                         "expectedOrderedStoreIds": [], "goldNegative": False, "filters": {}}],
        }
        records = [{
            "queryId": "q-1", "queryType": "sensory_without_menu", "repeatIndex": 0,
            "status": "format_error", "failureKind": "invalid_public_id",
            "providerHttpStatus": 200, "latencyMs": 100.0,
        }]

        aggregate = aggregate_staging_evaluation(evaluate_staging_records(dataset, records))

        self.assertEqual(aggregate["providerOrFormatSuccessCalls"], 0)
        self.assertEqual(aggregate["providerOrFormatSuccessRate"], 0.0)
        self.assertEqual(aggregate["formatErrorCalls"], 1)
        self.assertEqual(aggregate["ranking"]["hitsAt1"], 0)
        self.assertEqual(aggregate["failureBreakdown"], {"invalid_public_id": 1})

    def test_failed_negative_is_not_counted_as_true_negative_or_stable_empty_result(self):
        dataset = {
            "stores": [], "queries": [{
                "id": "q-negative", "queryType": "negative_or_ambiguous",
                "goldStoreIds": [], "expectedOrderedStoreIds": [],
                "goldNegative": True, "filters": {},
            }],
        }
        records = [{
            "queryId": "q-negative", "repeatIndex": repeat,
            "status": "provider_error_exhausted", "failureKind": "timeout",
            "providerHttpStatus": None, "latencyMs": 10_000.0,
        } for repeat in range(5)]

        aggregate = aggregate_staging_evaluation(evaluate_staging_records(dataset, records))

        self.assertEqual(aggregate["negativeFalsePositive"]["successfulQueries"], 0)
        self.assertEqual(aggregate["negativeFalsePositive"]["failedQueries"], 1)
        self.assertEqual(aggregate["wilson95"]["overallCorrectAt8"]["successes"], 0)
        self.assertEqual(aggregate["stability"]["queriesWithRepeats"], 0)

    def test_failed_filtered_query_is_excluded_from_violation_rate_denominator(self):
        dataset = {
            "stores": [{"id": "1", "region": "SEOUL", "categoryCode": "KOREAN"}],
            "queries": [{
                "id": "q-filter", "queryType": "composite_filter", "goldStoreIds": ["1"],
                "expectedOrderedStoreIds": [], "goldNegative": False,
                "filters": {"region": "SEOUL"},
            }],
        }
        records = [{
            "queryId": "q-filter", "repeatIndex": 0, "status": "format_error",
            "failureKind": "invalid_data", "providerHttpStatus": 200, "latencyMs": 100.0,
        }]

        aggregate = aggregate_staging_evaluation(evaluate_staging_records(dataset, records))

        self.assertEqual(aggregate["filterViolationRates"]["region"]["eligible"], 0)
        self.assertEqual(aggregate["filterViolationRates"]["region"]["failedApplicableQueries"], 1)

    @staticmethod
    def _record(query_id, repeat_index, store_ids, latency):
        return {
            "queryId": query_id, "repeatIndex": repeat_index, "status": "success",
            "failureKind": "", "providerHttpStatus": 200, "latencyMs": float(latency),
            "rankedStoreIds": store_ids,
        }


if __name__ == "__main__":
    unittest.main()
