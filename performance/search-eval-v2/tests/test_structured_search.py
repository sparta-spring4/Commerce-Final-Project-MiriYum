import unittest

from miriyum_search_eval.structured_search import (
    EvidenceSource,
    aggregate_structured_comparison,
    evaluate_structured_variants,
    extract_structured_food_evidence,
    prepare_structured_catalog,
    retrieve_structured_candidates,
)


FAMILIES = [
    {
        "id": "family-maratang",
        "canonical": "마라탕",
        "baseName": "마라탕",
        "aliases": ["마라 탕"],
        "variants": ["마라탕", "해물 마라탕"],
        "attributes": {
            "ingredient": "해물",
            "taste": "칼칼한",
            "broth": "국물",
            "method": "끓이기",
            "category": "SOUP",
            "aroma": "향긋한",
            "texture": "쫄깃한",
        },
    },
    {
        "id": "family-sweet-maratang",
        "canonical": "달콤한 마라탕",
        "baseName": "마라탕",
        "aliases": [],
        "variants": ["달콤한 마라탕"],
        "attributes": {
            "ingredient": "채소",
            "taste": "달콤한",
            "broth": "국물",
            "method": "끓이기",
            "category": "SOUP",
            "aroma": "은은한",
            "texture": "부드러운",
        },
    },
]


class StructuredFoodEvidenceTest(unittest.TestCase):
    def test_splits_compound_food_span_without_losing_the_raw_span(self):
        evidence = extract_structured_food_evidence(
            "칼칼한 해물 마라탕",
            families=FAMILIES,
            interpretation="MATCHABLE",
            llm_concepts=[],
        )

        self.assertEqual(evidence.raw_food_spans, ("칼칼한 해물 마라탕",))
        self.assertEqual(evidence.menu_families, ("마라탕",))
        self.assertEqual(evidence.ingredients, ("해물",))
        self.assertEqual(evidence.tastes, ("칼칼한",))
        self.assertEqual(evidence.broths, ())
        self.assertEqual(evidence.methods, ())
        self.assertEqual(evidence.sources["menuFamilies"], EvidenceSource.DETERMINISTIC)
        self.assertEqual(evidence.sources["tastes"], EvidenceSource.DETERMINISTIC)

    def test_llm_only_fills_dimensions_missing_from_deterministic_evidence(self):
        evidence = extract_structured_food_evidence(
            "칼칼한 마라탕",
            families=FAMILIES,
            interpretation="MATCHABLE",
            llm_concepts=["달콤한 해물 마라탕"],
        )

        self.assertEqual(evidence.tastes, ("칼칼한",))
        self.assertEqual(evidence.ingredients, ("해물",))
        self.assertEqual(evidence.sources["tastes"], EvidenceSource.DETERMINISTIC)
        self.assertEqual(evidence.sources["ingredients"], EvidenceSource.LLM)

    def test_abstention_does_not_use_llm_concepts_as_structured_evidence(self):
        evidence = extract_structured_food_evidence(
            "추천해줘",
            families=FAMILIES,
            interpretation="AMBIGUOUS",
            llm_concepts=["해물 마라탕"],
        )

        self.assertEqual(evidence.menu_families, ())
        self.assertEqual(evidence.ingredients, ())

    def test_specific_styled_family_wins_over_shared_base_name(self):
        families = [
            {
                **FAMILIES[0],
                "id": "family-plain",
                "canonical": "마라탕",
                "baseName": "마라탕",
                "variants": ["마라탕"],
                "attributes": {**FAMILIES[0]["attributes"], "taste": "얼얼한"},
            },
            {
                **FAMILIES[0],
                "id": "family-spicy",
                "canonical": "칼칼한 마라탕",
                "baseName": "마라탕",
                "variants": ["칼칼한 마라탕"],
                "attributes": {**FAMILIES[0]["attributes"], "taste": "칼칼한"},
            },
        ]

        evidence = extract_structured_food_evidence(
            "칼칼한 해물 마라탕 찾아줘",
            families=families,
            interpretation="MATCHABLE",
            llm_concepts=[],
        )

        self.assertEqual(evidence.menu_families, ("마라탕",))
        self.assertEqual(evidence.family_ids, ("family-spicy",))
        self.assertEqual(evidence.tastes, ("칼칼한",))

        plain = extract_structured_food_evidence(
            "마라탕 찾아줘",
            families=families,
            interpretation="MATCHABLE",
            llm_concepts=[],
        )
        self.assertEqual(plain.family_ids, ("family-plain",))


class StructuredCandidateTest(unittest.TestCase):
    def setUp(self):
        self.families = [
            FAMILIES[0],
            {
                **FAMILIES[1],
                "id": "family-spicy-vegetable",
                "canonical": "채소전골",
                "baseName": "채소전골",
                "variants": ["채소전골"],
                "attributes": {
                    **FAMILIES[1]["attributes"],
                    "taste": "칼칼한",
                    "ingredient": "채소",
                    "method": "끓이기",
                },
            },
        ]
        self.stores = [
            self._store("store-exact", recommendation=10, distance=400),
            self._store("store-broad", recommendation=900, distance=100),
        ]
        self.menus = [
            self._menu("menu-exact", "store-exact", "family-maratang", "해물 마라탕"),
            self._menu("menu-broad", "store-broad", "family-spicy-vegetable", "채소전골"),
        ]

    @staticmethod
    def _store(store_id, *, recommendation, distance):
        return {
            "id": store_id,
            "name": store_id,
            "region": "SEOUL",
            "ambience": "CASUAL",
            "verificationStatus": "APPROVED",
            "operationStatus": "OPEN",
            "recommendationScore": recommendation,
            "distanceMeters": distance,
            "rating": 4.0,
        }

    @staticmethod
    def _menu(menu_id, store_id, family_id, name):
        return {
            "id": menu_id,
            "storeId": store_id,
            "familyId": family_id,
            "name": name,
            "description": "",
            "category": "SOUP",
            "tags": [],
            "price": 10_000,
            "retired": False,
            "visibility": "VISIBLE",
        }

    def test_menu_free_candidate_requires_two_distinct_core_dimensions(self):
        evidence = extract_structured_food_evidence(
            "칼칼한 해물 음식",
            families=self.families,
            interpretation="MATCHABLE",
            llm_concepts=[],
        )

        result = retrieve_structured_candidates(
            evidence=evidence,
            families=self.families,
            stores=self.stores,
            menus=self.menus,
            filters={},
            sort="RECOMMENDED",
            ranking="current",
        )

        self.assertEqual(result.menu_ids, ("menu-exact",))
        self.assertEqual(result.evidence_counts, {"menu-exact": 2})

    def test_specific_family_id_is_not_reexpanded_by_shared_base_name(self):
        sibling = {
            **FAMILIES[0],
            "id": "family-sibling",
            "canonical": "직화 마라탕",
            "baseName": "마라탕",
            "variants": ["직화 마라탕"],
        }
        menus = [
            self._menu("menu-exact", "store-exact", "family-maratang", "마라탕"),
            self._menu("menu-broad", "store-broad", "family-sibling", "직화 마라탕"),
        ]
        evidence = extract_structured_food_evidence(
            "마라탕 찾아줘",
            families=[FAMILIES[0], sibling],
            interpretation="MATCHABLE",
            llm_concepts=[],
        )

        result = retrieve_structured_candidates(
            evidence=evidence,
            families=[FAMILIES[0], sibling],
            stores=self.stores,
            menus=menus,
            filters={},
            sort="RECOMMENDED",
            ranking="current",
        )

        self.assertEqual(evidence.family_ids, ("family-maratang",))
        self.assertEqual(result.menu_ids, ("menu-exact",))

    def test_generic_form_alone_never_creates_a_candidate(self):
        evidence = extract_structured_food_evidence(
            "면 추천해줘",
            families=self.families,
            interpretation="MATCHABLE",
            llm_concepts=[],
        )

        result = retrieve_structured_candidates(
            evidence=evidence,
            families=self.families,
            stores=self.stores,
            menus=self.menus,
            filters={},
            sort="RECOMMENDED",
            ranking="structured",
        )

        self.assertEqual(result.store_ids, ())

    def test_structured_ranking_isolated_from_current_recommendation_score(self):
        evidence = extract_structured_food_evidence(
            "칼칼한 해물 끓이기 음식",
            families=self.families,
            interpretation="MATCHABLE",
            llm_concepts=[],
        )

        current = retrieve_structured_candidates(
            evidence=evidence,
            families=self.families,
            stores=self.stores,
            menus=self.menus,
            filters={},
            sort="RECOMMENDED",
            ranking="current",
        )
        structured = retrieve_structured_candidates(
            evidence=evidence,
            families=self.families,
            stores=self.stores,
            menus=self.menus,
            filters={},
            sort="RECOMMENDED",
            ranking="structured",
        )

        self.assertEqual(current.store_ids, ("store-broad", "store-exact"))
        self.assertEqual(structured.store_ids, ("store-exact", "store-broad"))
        self.assertEqual(structured.evidence_counts, {
            "menu-exact": 3,
            "menu-broad": 2,
        })

    def test_prepared_catalog_preserves_structured_candidate_results(self):
        evidence = extract_structured_food_evidence(
            "칼칼한 해물 끓이기 음식",
            families=self.families,
            interpretation="MATCHABLE",
            llm_concepts=[],
        )
        expected = retrieve_structured_candidates(
            evidence=evidence,
            families=self.families,
            stores=self.stores,
            menus=self.menus,
            filters={},
            sort="RECOMMENDED",
            ranking="structured",
        )

        actual = retrieve_structured_candidates(
            evidence=evidence,
            families=self.families,
            stores=self.stores,
            menus=self.menus,
            filters={},
            sort="RECOMMENDED",
            ranking="structured",
            prepared_catalog=prepare_structured_catalog(
                families=self.families,
                stores=self.stores,
                menus=self.menus,
            ),
        )

        self.assertEqual(actual, expected)

    def test_abc_comparison_separates_candidate_gain_from_ranking_gain(self):
        query = {
            "id": "query-abc",
            "type": "sensory_without_menu",
            "text": "칼칼한 해물 끓이기 음식",
            "filters": {},
            "sort": "RECOMMENDED",
            "gold": {
                "familyIds": ["family-maratang"],
                "menuIds": ["menu-exact"],
                "storeIds": ["store-exact"],
                "negative": False,
                "forbiddenMenuIds": [],
                "forbiddenStoreIds": [],
            },
        }
        query["acceptableGold"] = dict(query["gold"])
        dataset = {
            "families": self.families,
            "stores": self.stores,
            "menus": self.menus,
        }
        record = {
            "queryId": "query-abc",
            "repeatIndex": 0,
            "status": "success",
            "interpretation": "MATCHABLE",
            "concepts": [],
        }
        baseline = {
            "finalApplicationByCutoff": {
                label: {"rankedStoreIds": []}
                for label in ("@8", "@20", "@50", "all")
            },
        }

        result = evaluate_structured_variants(
            dataset=dataset,
            query=query,
            record=record,
            baseline_call=baseline,
            prepared_catalog=prepare_structured_catalog(
                families=self.families,
                stores=self.stores,
                menus=self.menus,
            ),
        )
        aggregate = aggregate_structured_comparison([result])

        self.assertFalse(result["variants"]["A"]["cutoffs"]["@8"]["strictHit"])
        self.assertTrue(result["variants"]["B"]["cutoffs"]["@8"]["strictHit"])
        self.assertFalse(result["variants"]["B"]["cutoffs"]["@1"]["strictHit"])
        self.assertTrue(result["variants"]["C"]["cutoffs"]["@1"]["strictHit"])
        self.assertEqual(aggregate["pairedDeltas"]["AtoB"]["strict@8"], 1)
        self.assertEqual(aggregate["pairedDeltas"]["BtoC"]["strict@1"], 1)

    def test_repeated_structured_inputs_reuse_evidence_candidates_and_original(self):
        query = {
            "id": "query-cache",
            "type": "sensory_without_menu",
            "text": "칼칼한 해물 끓이기 음식",
            "filters": {},
            "sort": "RECOMMENDED",
            "gold": {
                "familyIds": ["family-maratang"],
                "menuIds": ["menu-exact"],
                "storeIds": ["store-exact"],
                "negative": False,
                "forbiddenMenuIds": [],
                "forbiddenStoreIds": [],
            },
        }
        dataset = {
            "families": self.families,
            "stores": self.stores,
            "menus": self.menus,
        }
        baseline = {
            "finalApplicationByCutoff": {
                label: {"rankedStoreIds": []}
                for label in ("@8", "@20", "@50", "all")
            },
        }
        prepared = prepare_structured_catalog(
            families=self.families,
            stores=self.stores,
            menus=self.menus,
        )
        cache = {}
        record = {
            "queryId": "query-cache",
            "repeatIndex": 0,
            "status": "success",
            "interpretation": "MATCHABLE",
            "concepts": [],
        }

        first = evaluate_structured_variants(
            dataset=dataset,
            query=query,
            record=record,
            baseline_call=baseline,
            prepared_catalog=prepared,
            retrieval_cache=cache,
        )
        second = evaluate_structured_variants(
            dataset=dataset,
            query=query,
            record={**record, "repeatIndex": 1},
            baseline_call=baseline,
            prepared_catalog=prepared,
            retrieval_cache=cache,
        )

        self.assertEqual(len(cache), 3)
        self.assertEqual(first["variants"], second["variants"])

    def test_true_no_answer_false_positive_fails_structured_gate(self):
        query = {
            "id": "query-negative",
            "type": "negative_or_ambiguous_voice",
            "subtype": "true_no_answer",
            "text": "칼칼한 해물 음식",
            "filters": {},
            "sort": "RECOMMENDED",
            "gold": {
                "familyIds": [],
                "menuIds": [],
                "storeIds": [],
                "negative": True,
                "forbiddenMenuIds": [],
                "forbiddenStoreIds": [],
            },
        }
        query["acceptableGold"] = dict(query["gold"])
        result = evaluate_structured_variants(
            dataset={
                "families": self.families,
                "stores": self.stores,
                "menus": self.menus,
            },
            query=query,
            record={
                "queryId": "query-negative",
                "repeatIndex": 0,
                "status": "success",
                "interpretation": "MATCHABLE",
                "concepts": [],
            },
            baseline_call={
                "finalApplicationByCutoff": {
                    label: {"rankedStoreIds": []}
                    for label in ("@8", "@20", "@50", "all")
                },
            },
        )

        aggregate = aggregate_structured_comparison([result])

        self.assertEqual(aggregate["safety"]["A"]["trueNoAnswerFalsePositives"], 0)
        self.assertEqual(aggregate["safety"]["B"]["trueNoAnswerFalsePositives"], 1)
        self.assertFalse(aggregate["gate"]["passed"])
        self.assertIn("B_true_no_answer_false_positive_regression", aggregate["gate"]["fatalReasons"])

    def test_aggregate_reports_each_query_type_and_false_positives_by_cutoff(self):
        query = {
            "id": "query-breakdown",
            "type": "alias_bidirectional",
            "text": "칼칼한 해물 음식",
            "filters": {},
            "sort": "RECOMMENDED",
            "gold": {
                "familyIds": ["family-maratang"],
                "menuIds": ["menu-exact"],
                "storeIds": ["store-exact"],
                "negative": False,
                "forbiddenMenuIds": [],
                "forbiddenStoreIds": [],
            },
        }
        query["acceptableGold"] = dict(query["gold"])
        result = evaluate_structured_variants(
            dataset={
                "families": self.families,
                "stores": self.stores,
                "menus": self.menus,
            },
            query=query,
            record={
                "queryId": query["id"],
                "repeatIndex": 0,
                "status": "success",
                "interpretation": "MATCHABLE",
                "concepts": [],
            },
            baseline_call={
                "finalApplicationByCutoff": {
                    label: {"rankedStoreIds": []}
                    for label in ("@8", "@20", "@50", "all")
                },
            },
        )

        aggregate = aggregate_structured_comparison([result])

        breakdown = aggregate["byQueryType"]["alias_bidirectional"]
        self.assertEqual(breakdown["uniqueQueries"], 1)
        self.assertEqual(breakdown["variants"]["B"]["strict@8"]["successes"], 1)
        self.assertEqual(
            aggregate["safety"]["B"]["falsePositivesByCutoff"]["@8"]["strict"],
            0,
        )

    def test_alias_matching_regression_fails_gate_even_without_safety_leak(self):
        result = {
            "queryId": "query-alias-regression",
            "repeatIndex": 0,
            "queryType": "alias_bidirectional",
            "querySubtype": None,
            "goldNegative": False,
            "variants": {},
        }
        for variant, hit in (("A", True), ("B", False), ("C", False)):
            result["variants"][variant] = {
                "cutoffs": {
                    label: {
                        "rankedStoreIds": ["store-exact"] if hit else [],
                        "strictHit": hit,
                        "acceptableHit": hit,
                        "strictRecall": float(hit),
                        "acceptableRecall": float(hit),
                        "strictFalsePositive": False,
                        "acceptableFalsePositive": False,
                        "ranking": {"mrr": float(hit), "ndcg": float(hit)},
                    }
                    for label in ("@1", "@3", "@5", "@8", "@20", "@50", "all")
                },
                "safety": {
                    "closedOrForbiddenStoreLeak": False,
                    "privateOrHistoricalMenuLeak": False,
                    "filterViolation": False,
                },
            }

        aggregate = aggregate_structured_comparison([result])

        self.assertFalse(aggregate["gate"]["passed"])
        self.assertIn(
            "B_alias_bidirectional_strict_at8_regression",
            aggregate["gate"]["fatalReasons"],
        )


if __name__ == "__main__":
    unittest.main()
