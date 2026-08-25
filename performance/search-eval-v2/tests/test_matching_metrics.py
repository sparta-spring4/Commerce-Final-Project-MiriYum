import math
import unittest

from miriyum_search_eval.matching import (
    MatchMode,
    deterministic_remaining_keyword,
    merge_application_candidates,
    most_specific_explicit_names,
    prepare_catalog,
    retrieve_candidates,
    retrieve_evidence_candidates,
    retrieve_original_candidates,
)
from miriyum_search_eval.metrics import ranking_metrics, stability_metrics, wilson_interval


class MatchingTest(unittest.TestCase):
    def setUp(self):
        self.families = [{
            "id": "family-000",
            "canonical": "짬뽕",
            "aliases": ["해물짬뽕"],
            "variants": ["짬뽕", "불향 해물 짬뽕"],
            "attributes": {"category": "NOODLE", "ingredient": "해물", "taste": "얼큰한", "method": "끓임", "broth": "국물"},
        }]
        self.stores = [
            {"id": "store-open", "region": "SEOUL", "ambience": "혼밥", "verificationStatus": "APPROVED", "operationStatus": "OPEN", "distanceMeters": 100, "rating": 4.5, "recommendationScore": 90},
            {"id": "store-closed", "region": "SEOUL", "ambience": "혼밥", "verificationStatus": "APPROVED", "operationStatus": "CLOSED", "distanceMeters": 10, "rating": 5.0, "recommendationScore": 100},
        ]
        self.menus = [
            {"id": "menu-short", "storeId": "store-open", "familyId": "family-000", "name": "짬뽕", "description": "해물", "category": "NOODLE", "tags": ["얼큰한"], "price": 10_000, "retired": False, "visibility": "VISIBLE"},
            {"id": "menu-generic", "storeId": "store-open", "familyId": "family-999", "name": "면", "description": "일반 면", "category": "NOODLE", "tags": [], "price": 8_000, "retired": False, "visibility": "VISIBLE"},
            {"id": "menu-closed", "storeId": "store-closed", "familyId": "family-000", "name": "짬뽕", "description": "해물", "category": "NOODLE", "tags": [], "price": 9_000, "retired": False, "visibility": "VISIBLE"},
            {"id": "menu-private", "storeId": "store-open", "familyId": "family-000", "name": "비밀 짬뽕", "description": "해물", "category": "NOODLE", "tags": [], "price": 9_000, "retired": False, "visibility": "PRIVATE"},
            {"id": "menu-specific-private", "storeId": "store-open", "familyId": "family-001", "name": "칼칼한 짬뽕", "description": "해물", "category": "NOODLE", "tags": [], "price": 9_000, "retired": False, "visibility": "PRIVATE"},
            {"id": "menu-specific-retired", "storeId": "store-open", "familyId": "family-001", "name": "옛날 짬뽕", "description": "해물", "category": "NOODLE", "tags": [], "price": 9_000, "retired": True, "visibility": "VISIBLE"},
        ]

    def test_separates_legacy_one_way_from_current_reverse(self):
        result = retrieve_candidates(
            concepts=["불향 해물 짬뽕"], families=self.families, stores=self.stores,
            menus=self.menus, filters={}, sort="RECOMMENDED",
        )

        self.assertEqual(result.one_way_menu_ids, ())
        self.assertEqual(result.bidirectional_menu_ids, ("menu-short",))
        self.assertEqual(result.provenance["menu-short"], MatchMode.REVERSE)

    def test_generic_reverse_tokens_are_denied(self):
        result = retrieve_candidates(
            concepts=["시원한 면 요리"], families=self.families, stores=self.stores,
            menus=self.menus, filters={}, sort="RECOMMENDED",
        )

        self.assertNotIn("menu-generic", result.bidirectional_menu_ids)

    def test_alias_matches_and_state_filters_do_not_leak(self):
        result = retrieve_candidates(
            concepts=["해물짬뽕"], families=self.families, stores=self.stores,
            menus=self.menus, filters={"region": "SEOUL", "maxPrice": 12_000}, sort="DISTANCE",
        )

        self.assertEqual(result.bidirectional_menu_ids, ("menu-short",))
        self.assertEqual(result.provenance["menu-short"], MatchMode.REVERSE)
        self.assertEqual(result.alias_menu_ids, ("menu-short",))
        self.assertNotIn("menu-closed", result.bidirectional_menu_ids)
        self.assertNotIn("menu-private", result.bidirectional_menu_ids)

    def test_prepared_catalog_reuses_one_normalized_corpus_snapshot(self):
        prepared = prepare_catalog(families=self.families, stores=self.stores, menus=self.menus)
        self.menus[0]["name"] = "변경된 이름"

        result = retrieve_candidates(
            concepts=["불향 해물 짬뽕"], families=self.families, stores=self.stores,
            menus=self.menus, filters={}, sort="RECOMMENDED", prepared_catalog=prepared,
        )

        self.assertIn("menu-short", result.bidirectional_menu_ids)

    def test_original_search_recovers_guarded_menu_name_inside_remaining_keyword(self):
        exact = retrieve_original_candidates(
            remaining_keyword="짬뽕", stores=self.stores, menus=self.menus,
            filters={}, sort="RECOMMENDED",
        )
        natural_language = retrieve_original_candidates(
            remaining_keyword="짬뽕 파는 매장 중 추천순으로 보여줘",
            stores=self.stores, menus=self.menus, filters={}, sort="RECOMMENDED",
        )

        self.assertEqual(exact.store_ids, ("store-open",))
        generic = retrieve_original_candidates(
            remaining_keyword="얼큰한 면 파는 매장", stores=self.stores, menus=self.menus,
            filters={}, sort="RECOMMENDED",
        )

        self.assertEqual(natural_language.store_ids, ("store-open",))
        self.assertEqual(generic.store_ids, ())

    def test_original_search_does_not_fall_back_to_shorter_overlapping_menu_name(self):
        result = retrieve_original_candidates(
            remaining_keyword="칼칼한 짬뽕 파는 매장", stores=self.stores,
            menus=self.menus, filters={}, sort="RECOMMENDED",
        )

        self.assertEqual(result.store_ids, ())

    def test_retired_longer_name_does_not_suppress_current_shorter_name(self):
        result = retrieve_original_candidates(
            remaining_keyword="옛날 짬뽕 파는 매장", stores=self.stores,
            menus=self.menus, filters={}, sort="RECOMMENDED",
        )

        self.assertEqual(result.store_ids, ("store-open",))

    def test_remaining_keyword_removes_frozen_structured_spans(self):
        query = {
            "text": "SEOUL에서 12000원 이하 혼밥 분위기의 짬뽕 찾아줘",
            "filters": {"region": "SEOUL", "ambience": "혼밥", "maxPrice": 12000},
        }

        self.assertEqual(
            deterministic_remaining_keyword(query),
            "에서 분위기의 짬뽕 찾아줘",
        )

    def test_most_specific_filter_runs_before_final_hundred_name_bound(self):
        names = {"가" * length for length in range(2, 101)} | {"나다", "라마"}

        self.assertEqual(
            most_specific_explicit_names(names),
            ("가" * 100, "나다", "라마"),
        )

    def test_most_specific_filter_matches_ai_ci_unicode_behavior(self):
        self.assertEqual(
            most_specific_explicit_names({"Café", "Cafe\u0301 Latte"}),
            ("Cafe\u0301 Latte",),
        )

    def test_application_merge_keeps_original_results_then_adds_supplement(self):
        merged = merge_application_candidates(
            original_store_ids=("s2", "s1"), supplement_store_ids=("s1", "s3", "s4"),
            page_size=3,
        )

        self.assertEqual(merged, ("s2", "s1", "s3"))

    def test_evidence_retrieval_requires_multiple_query_attributes_for_name_free_match(self):
        families = [
            *self.families,
            {
                "id": "family-999", "canonical": "해물튀김", "aliases": [],
                "variants": ["해물튀김"],
                "attributes": {
                    "category": "SEAFOOD", "ingredient": "해물", "taste": "담백한",
                    "method": "튀김", "broth": "없음",
                },
            },
        ]
        menus = [
            self.menus[0],
            {
                "id": "menu-distractor", "storeId": "store-open", "familyId": "family-999",
                "name": "해물튀김", "description": "해물 튀김", "category": "SEAFOOD",
                "tags": ["해물", "담백한", "튀김"], "price": 10_000,
                "retired": False, "visibility": "VISIBLE",
            },
        ]

        result = retrieve_evidence_candidates(
            query_text="해물 재료, 얼큰한 맛, 끓임 방식, 국물 음식",
            concepts=[], families=families, stores=self.stores, menus=menus,
            filters={}, sort="RECOMMENDED",
        )

        self.assertEqual(result.store_ids, ("store-open",))
        self.assertEqual(result.menu_ids, ("menu-short",))
        self.assertEqual(result.evidence_counts["menu-short"], 4)
        self.assertNotIn("menu-distractor", result.menu_ids)

    def test_evidence_retrieval_preserves_name_matching_when_query_has_explicit_menu(self):
        families = [
            *self.families,
            {
                "id": "family-999", "canonical": "해물튀김", "baseName": "해물튀김",
                "aliases": [], "variants": ["해물튀김"],
                "attributes": {
                    "category": "SEAFOOD", "ingredient": "해물", "taste": "담백한",
                    "method": "튀김", "broth": "없음",
                },
            },
        ]
        menus = [
            self.menus[0],
            {
                "id": "menu-distractor", "storeId": "store-open", "familyId": "family-999",
                "name": "해물튀김", "description": "해물 튀김", "category": "SEAFOOD",
                "tags": ["해물", "담백한", "튀김"], "price": 10_000,
                "retired": False, "visibility": "VISIBLE",
            },
        ]

        result = retrieve_evidence_candidates(
            query_text="짬뽕 중 해물 담백한 튀김 없음 조건",
            concepts=["짬뽕"], families=families, stores=self.stores, menus=menus,
            filters={}, sort="RECOMMENDED",
        )

        self.assertEqual(result.menu_ids, ("menu-short",))


class MetricsTest(unittest.TestCase):
    def test_ranking_metrics(self):
        metrics = ranking_metrics(["s2", "s1", "s4"], ["s1", "s2", "s3"], ks=(1, 3))

        self.assertEqual(metrics["recall@1"], 1 / 3)
        self.assertEqual(metrics["recall@3"], 2 / 3)
        self.assertEqual(metrics["mrr"], 1.0)
        self.assertGreater(metrics["ndcg@3"], 0.0)
        self.assertLessEqual(metrics["ndcg@3"], 1.0)

    def test_stability_uses_repetitions_not_independent_samples(self):
        metrics = stability_metrics([
            ["s1", "s2"], ["s1", "s2"], ["s2", "s1"], ["s1"], ["s1", "s3"],
        ])

        self.assertEqual(metrics["top1Stability"], 4 / 5)
        self.assertAlmostEqual(metrics["pairwiseJaccard"], 0.6)

    def test_wilson_interval_uses_unique_query_count(self):
        low, high = wilson_interval(successes=1800, total=2000)

        self.assertLess(low, 0.9)
        self.assertGreater(high, 0.9)
        self.assertTrue(math.isclose(low, 0.8861, abs_tol=0.001))


if __name__ == "__main__":
    unittest.main()
