import unittest

from miriyum_search_eval.reporting import render_report


class ReportingTest(unittest.TestCase):
    def test_report_labels_merged_bidirectional_as_actual_and_one_way_as_legacy(self):
        binary = {"successes": 1000, "total": 2000, "rate": 0.5, "wilson95": {"low": 0.4, "high": 0.6}}
        aggregate = {
            "uniqueQuerySuccess": {key: binary for key in (
                "providerFormat", "semantic", "strictFamilySemantic", "acceptableFamilySemantic",
                "oneWay", "bidirectional", "strictFinal@8", "strictFinal@20", "strictFinal@50",
                "strictFinalAll", "acceptableFinal@8", "acceptableFinal@20",
                "acceptableFinal@50", "acceptableFinalAll",
            )},
            "usage": {"inputTokens": 10, "outputTokens": 2, "costUsd": 0.001},
            "latencyMs": {"p50": 1, "p95": 2, "p99": 3, "max": 4, "over2SecondsRate": 0},
            "statisticalUnit": {"calls": 100, "uniqueQueries": 100},
            "ranking": {"recall@5": 0.5, "mrr": 0.5, "ndcg@8": 0.5},
            "stability": {"meanTop1Stability": 0.5, "meanPairwiseJaccard": 0.5},
            "negativeFalsePositiveRate": 0.0,
            "leakageAndFilters": {"closedLeakRate": 0, "privateOrHistoricalLeakRate": 0, "filterViolationRate": 0},
            "abstentionRate": 0.2,
            "matchCounts": {mode: 0 for mode in ("exact", "forward", "reverse", "alias")},
            "falsePositiveCounts": {mode: 0 for mode in ("exact", "forward", "reverse", "alias")},
            "queryTypeBreakdown": {},
            "candidateCutoffs": {
                label: {"strictHitRate": 0.5, "acceptableHitRate": 0.6, "strictRecall": 0.4, "acceptableRecall": 0.5}
                for label in ("@8", "@20", "@50", "all")
            },
            "attributeUnderstanding": {"uniqueQuerySuccess": {}},
        }
        gate = {"passed": True, "resumeDuplicateCalls": 0, "projectedCompletionCostUsd": 0.5}
        metadata = {
            "executedAtUtc": "2026-08-24T00:00:00Z", "commitSha": "3a2d5bef0000",
            "seed": 20260823, "schemaVersion": "miriyum-search-eval-v2",
            "requestedModel": "gpt-4o-mini", "returnedModels": ["gpt-4o-mini-2024-07-18"],
            "paidExecution": {
                "analyzedCalls": 10000, "paidProviderCalls": 10012,
                "duplicateOverheadCalls": 12, "actualPaidCostUsd": 0.780873,
            },
            "checkpointReuse": {
                "migrated": 10000, "newProviderCalls": 0, "incrementalCostUsd": 0.0,
                "sourcePaidExecution": {
                    "paidProviderCalls": 10012, "actualPaidCostUsd": 0.780873,
                },
            },
        }

        report = render_report(aggregate=aggregate, pilot_gate=gate, metadata=metadata)

        self.assertIn("actual current bidirectional", report)
        self.assertIn("legacy one-way counterfactual", report)
        self.assertNotIn("simulated Issue #589", report)
        self.assertIn("abstention rate: 20.00%", report)
        self.assertIn("paid provider calls: 10,012", report)
        self.assertIn("duplicate overhead: 12", report)
        self.assertIn("엄격 메뉴군 의미 성공", report)
        self.assertIn("허용 메뉴군 의미 성공", report)
        self.assertIn("| @20 |", report)
        self.assertIn("| 전체 후보 |", report)
        self.assertIn("source paid ledger: 10,012 calls; $0.780873", report)


if __name__ == "__main__":
    unittest.main()
