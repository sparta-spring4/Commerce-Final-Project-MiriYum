import unittest

from miriyum_search_eval.structured_search import extract_structured_food_evidence
from miriyum_search_eval.hybrid_search import (
    ACTUAL_FOOD_EVIDENCE_VARIANT,
    aggregate_hybrid_comparison,
    evaluate_hybrid_variants,
    informative_query_tokens,
    prepare_hybrid_catalog,
    retrieve_lexical_candidates,
)


FAMILIES = [
    {
        "id": "family-target",
        "canonical": "해물국",
        "baseName": "해물국",
        "aliases": [],
        "variants": ["해물국"],
        "attributes": {
            "ingredient": "해물", "taste": "칼칼한", "broth": "국물",
            "method": "끓임", "category": "SOUP", "aroma": "바다향",
            "texture": "탱글한",
        },
    },
    {
        "id": "family-distractor",
        "canonical": "채소국",
        "baseName": "채소국",
        "aliases": [],
        "variants": ["채소국"],
        "attributes": {
            "ingredient": "채소", "taste": "담백한", "broth": "국물",
            "method": "끓임", "category": "SOUP", "aroma": "풀향",
            "texture": "부드러운",
        },
    },
]


def store(store_id, *, recommendation=100, status="OPEN"):
    return {
        "id": store_id, "name": store_id, "region": "SEOUL",
        "ambience": "CASUAL", "verificationStatus": "APPROVED",
        "operationStatus": status, "recommendationScore": recommendation,
        "distanceMeters": 100, "rating": 4.0,
    }


def menu(menu_id, store_id, family_id, name, description, tags=(), visibility="VISIBLE"):
    return {
        "id": menu_id, "storeId": store_id, "familyId": family_id,
        "name": name, "description": description, "tags": list(tags),
        "category": "SOUP", "price": 10_000, "retired": False,
        "visibility": visibility,
    }


class HybridSearchTest(unittest.TestCase):
    def setUp(self):
        self.stores = [
            store("store-target", recommendation=10),
            store("store-distractor", recommendation=900),
            store("store-closed", status="CLOSED"),
        ]
        self.menus = [
            menu(
                "menu-target", "store-target", "family-target", "해물국",
                "칼칼한 바다향 국물", ("탱글한",),
            ),
            menu(
                "menu-distractor", "store-distractor", "family-distractor", "채소국",
                "담백한 풀향 국물", ("부드러운",),
            ),
            menu(
                "menu-closed", "store-closed", "family-target", "해물국",
                "칼칼한 바다향 국물", ("탱글한",),
            ),
        ]
        self.catalog = prepare_hybrid_catalog(
            families=FAMILIES, stores=self.stores, menus=self.menus,
        )

    def test_filter_and_request_words_do_not_become_lexical_evidence(self):
        tokens = informative_query_tokens(
            "SEOUL에서 칼칼한 바다향 음식 추천해줘",
            filters={"region": "SEOUL"},
        )

        self.assertEqual(tokens, ("칼칼한", "바다향"))

    def test_lexical_candidate_requires_two_distinct_tokens_and_filters_state(self):
        one_token = retrieve_lexical_candidates(
            query_text="바다향 음식 추천해줘", filters={}, sort="RECOMMENDED",
            catalog=self.catalog,
        )
        two_tokens = retrieve_lexical_candidates(
            query_text="칼칼한 바다향 음식 추천해줘", filters={}, sort="RECOMMENDED",
            catalog=self.catalog,
        )

        self.assertEqual(one_token.store_ids, ())
        self.assertEqual(two_tokens.store_ids, ("store-target",))
        self.assertEqual(two_tokens.menu_match_counts, {"menu-target": 2})

    def test_lexical_candidate_uses_family_aroma_texture_with_korean_suffixes(self):
        result = retrieve_lexical_candidates(
            query_text="바다향이고 탱글한 식감인 음식 추천해줘",
            filters={}, sort="RECOMMENDED", catalog=self.catalog,
        )

        self.assertEqual(result.store_ids, ("store-target",))
        self.assertEqual(result.menu_match_counts, {"menu-target": 2})

    def test_hybrid_keeps_explicit_menu_ranking_unchanged(self):
        query = self._query("해물국 찾아줘", query_type="alias_bidirectional")
        structured_call = self._structured_call(query, menu_family=True)

        result = evaluate_hybrid_variants(
            dataset=self._dataset(), query=query, structured_call=structured_call,
            embedding_menu_scores=(("menu-distractor", 0.99),),
            prepared_catalog=self.catalog,
        )

        for variant in ("D", "E", "F", "G"):
            self.assertEqual(
                result["variants"][variant]["cutoffs"]["@20"]["rankedStoreIds"],
                ["store-target"],
            )

    def test_llm_inferred_menu_family_does_not_block_sensory_expansion(self):
        query = self._query("칼칼한 바다향 국물 음식 추천해줘")
        structured_call = self._structured_call(query, menu_family=False)
        structured_call["evidence"] = {
            **structured_call["evidence"],
            "menuFamilies": ["채소국"],
            "familyIds": ["family-distractor"],
            "sources": {
                **structured_call["evidence"]["sources"],
                "menuFamilies": "LLM",
            },
        }

        result = evaluate_hybrid_variants(
            dataset=self._dataset(), query=query, structured_call=structured_call,
            embedding_menu_scores=(("menu-target", 0.99),),
            prepared_catalog=self.catalog,
        )

        self.assertIn("menu-target", result["embeddingCandidateMenuIds"])
        self.assertEqual(
            result["variants"]["G"]["cutoffs"]["@1"]["rankedStoreIds"],
            ["store-target"],
        )

    def test_hybrid_does_not_expand_without_two_structured_food_dimensions(self):
        query = self._query("칼칼한 바다향 분위기 좋은 곳 추천해줘")
        structured_call = self._structured_call(query, menu_family=False)
        structured_call["evidence"] = {
            **structured_call["evidence"],
            "ingredients": [], "tastes": [], "broths": [], "methods": [],
        }

        result = evaluate_hybrid_variants(
            dataset=self._dataset(), query=query, structured_call=structured_call,
            embedding_menu_scores=(("menu-target", 0.99),),
            prepared_catalog=self.catalog,
        )

        self.assertEqual(result["lexicalCandidateMenuIds"], [])
        self.assertEqual(result["embeddingCandidateMenuIds"], [])
        for variant in ("E", "F", "G"):
            self.assertEqual(
                result["variants"][variant]["cutoffs"]["all"]["rankedStoreIds"],
                ["store-distractor"],
            )

    def test_g_reranks_menu_free_food_evidence_without_losing_union(self):
        query = self._query("칼칼한 바다향 국물 음식 추천해줘")
        structured_call = self._structured_call(query, menu_family=False)

        result = evaluate_hybrid_variants(
            dataset=self._dataset(), query=query, structured_call=structured_call,
            embedding_menu_scores=(("menu-distractor", 0.99), ("menu-target", 0.80)),
            prepared_catalog=self.catalog,
        )

        self.assertEqual(
            result["variants"]["F"]["cutoffs"]["all"]["rankedStoreIds"],
            ["store-distractor", "store-target"],
        )
        self.assertEqual(
            result["variants"]["G"]["cutoffs"]["@1"]["rankedStoreIds"],
            ["store-target"],
        )
        self.assertEqual(
            set(result["variants"]["G"]["cutoffs"]["all"]["rankedStoreIds"]),
            {"store-target", "store-distractor"},
        )

    def test_aggregate_reports_four_variants_targets_and_unique_query_unit(self):
        query = self._query("칼칼한 바다향 국물 음식 추천해줘")
        result = evaluate_hybrid_variants(
            dataset=self._dataset(), query=query,
            structured_call=self._structured_call(query, menu_family=False),
            embedding_menu_scores=(("menu-target", 0.80),),
            prepared_catalog=self.catalog,
        )

        aggregate = aggregate_hybrid_comparison([result])

        self.assertEqual(aggregate["statisticalUnit"]["uniqueQueries"], 1)
        self.assertEqual(tuple(aggregate["variants"]), ("D", "E", "F", "G", "H"))
        self.assertEqual(aggregate["pairedDeltas"]["FtoG"]["strict@1"], 1)
        self.assertEqual(aggregate["targets"]["acceptable@20"], {
            "targetRate": 0.9, "actualRate": 1.0, "achieved": True,
        })
        self.assertTrue(aggregate["gate"]["passed"])

    def test_negative_false_positive_regression_fails_hybrid_gate(self):
        query = self._query("칼칼한 바다향 국물 음식 추천해줘")
        result = evaluate_hybrid_variants(
            dataset=self._dataset(), query=query,
            structured_call=self._structured_call(query, menu_family=False),
            embedding_menu_scores=(("menu-target", 0.80),),
            prepared_catalog=self.catalog,
        )
        result["goldNegative"] = True
        for variant in ("D", "E", "F", "G"):
            for cutoff in result["variants"][variant]["cutoffs"].values():
                cutoff["strictFalsePositive"] = variant != "D"
                cutoff["acceptableFalsePositive"] = variant != "D"

        aggregate = aggregate_hybrid_comparison([result])

        self.assertFalse(aggregate["gate"]["passed"])
        self.assertIn("G_all_negative_false_positive_regression", aggregate["gate"]["fatalReasons"])

    def test_h_actual_requires_two_dimensions_on_the_same_menu(self):
        query = self._query("칼칼한 해물 음식 추천해줘")
        structured_call = self._structured_call(query, menu_family=False)
        for cutoff in structured_call["variants"]["C"]["cutoffs"].values():
            cutoff["rankedStoreIds"] = []
        split_menus = [
            menu("menu-taste", "store-target", "family-target", "오늘국", "칼칼한"),
            menu("menu-ingredient", "store-target", "family-target", "내일국", "해물"),
        ]
        catalog = prepare_hybrid_catalog(
            families=FAMILIES, stores=self.stores, menus=split_menus,
        )

        result = evaluate_hybrid_variants(
            dataset={"families": FAMILIES, "stores": self.stores, "menus": split_menus},
            query=query, structured_call=structured_call,
            embedding_menu_scores=(), prepared_catalog=catalog,
        )

        self.assertEqual(
            result["variants"][ACTUAL_FOOD_EVIDENCE_VARIANT]["cutoffs"]["all"]["rankedStoreIds"],
            [],
        )

    def test_h_actual_orders_explicit_alias_family_and_dimension_scores(self):
        stores = [store(f"store-{index}", recommendation=100) for index in range(4)]
        menus = [
            menu("menu-explicit", "store-0", "family-target", "불향 해물 짬뽕", "칼칼한 해물 국물"),
            menu("menu-alias", "store-1", "family-target", "옛날 짬뽕", "칼칼한 해물"),
            menu("menu-dim3", "store-2", "family-target", "오늘의 요리", "칼칼한 해물 국물"),
            menu("menu-dim2", "store-3", "family-target", "오늘의 별미", "칼칼한 해물"),
        ]
        query = self._query("불향 해물 짬뽕처럼 칼칼한 해물 국물")
        query["gold"]["storeIds"] = [store["id"] for store in stores]
        query["acceptableGold"] = dict(query["gold"])
        structured_call = self._structured_call(query, menu_family=False)
        for cutoff in structured_call["variants"]["C"]["cutoffs"].values():
            cutoff["rankedStoreIds"] = []
        catalog = prepare_hybrid_catalog(families=FAMILIES, stores=stores, menus=menus)

        result = evaluate_hybrid_variants(
            dataset={"families": FAMILIES, "stores": stores, "menus": menus},
            query=query, structured_call=structured_call,
            embedding_menu_scores=(), prepared_catalog=catalog,
        )

        self.assertEqual(
            result["variants"][ACTUAL_FOOD_EVIDENCE_VARIANT]["cutoffs"]["all"]["rankedStoreIds"],
            ["store-0", "store-1", "store-2", "store-3"],
        )

    def test_h_actual_excludes_store_menu_state_and_filter_violations(self):
        unapproved = store("store-unapproved")
        unapproved["verificationStatus"] = "PENDING"
        stores = self.stores + [unapproved, store("store-other-region")]
        stores[-1]["region"] = "BUSAN"
        menus = self.menus + [
            menu("menu-public", "store-target", "family-target", "짬뽕", "칼칼한 해물"),
            menu("menu-unapproved", "store-unapproved", "family-target", "짬뽕", "칼칼한 해물"),
            menu("menu-private", "store-target", "family-target", "짬뽕", "칼칼한 해물", visibility="PRIVATE"),
            {**menu("menu-retired", "store-target", "family-target", "짬뽕", "칼칼한 해물"), "retired": True},
            {**menu("menu-past", "store-target", "family-target", "짬뽕", "칼칼한 해물"),
             "publishedVersion": 2, "versions": [{"version": 2, "status": "SUPERSEDED", "name": "짬뽕"}]},
            menu("menu-other-region", "store-other-region", "family-target", "짬뽕", "칼칼한 해물"),
        ]
        query = self._query("짬뽕")
        query["filters"] = {"region": "SEOUL"}
        structured_call = self._structured_call(query, menu_family=True)
        catalog = prepare_hybrid_catalog(families=FAMILIES, stores=stores, menus=menus)

        result = evaluate_hybrid_variants(
            dataset={"families": FAMILIES, "stores": stores, "menus": menus},
            query=query, structured_call=structured_call,
            embedding_menu_scores=(), prepared_catalog=catalog,
        )

        variant = result["variants"][ACTUAL_FOOD_EVIDENCE_VARIANT]
        self.assertEqual(variant["cutoffs"]["all"]["rankedStoreIds"], ["store-target"])
        self.assertFalse(any(variant["safety"].values()))

    def test_h_actual_generic_form_token_alone_never_creates_candidates(self):
        query = self._query("면 추천해줘")
        structured_call = self._structured_call(query, menu_family=False)
        for cutoff in structured_call["variants"]["C"]["cutoffs"].values():
            cutoff["rankedStoreIds"] = []
        result = evaluate_hybrid_variants(
            dataset=self._dataset(), query=query,
            structured_call=structured_call,
            embedding_menu_scores=(), prepared_catalog=self.catalog,
        )

        self.assertEqual(
            result["variants"][ACTUAL_FOOD_EVIDENCE_VARIANT]["cutoffs"]["all"]["rankedStoreIds"],
            [],
        )

    def test_h_actual_preserves_validated_baseline_candidates(self):
        query = self._query("검색어와 무관한 기존 후보")
        structured_call = self._structured_call(query, menu_family=False)

        result = evaluate_hybrid_variants(
            dataset=self._dataset(), query=query, structured_call=structured_call,
            embedding_menu_scores=(), prepared_catalog=self.catalog,
        )

        self.assertEqual(
            result["variants"][ACTUAL_FOOD_EVIDENCE_VARIANT]["cutoffs"]["all"]["rankedStoreIds"],
            ["store-distractor"],
        )

    def test_h_actual_reports_every_cutoff_and_actual_gate(self):
        query = self._query("칼칼한 해물 음식 추천해줘")
        result = evaluate_hybrid_variants(
            dataset=self._dataset(), query=query,
            structured_call=self._structured_call(query, menu_family=False),
            embedding_menu_scores=(), prepared_catalog=self.catalog,
        )
        aggregate = aggregate_hybrid_comparison([result])

        self.assertEqual(
            tuple(result["variants"][ACTUAL_FOOD_EVIDENCE_VARIANT]["cutoffs"]),
            ("@1", "@3", "@5", "@8", "@20", "@50", "all"),
        )
        self.assertEqual(tuple(aggregate["variants"]), ("D", "E", "F", "G", "H"))
        self.assertTrue(aggregate["gate"]["actualApplication"])
        self.assertEqual(
            aggregate["gate"]["variant"], "actual-application-predicate-food-evidence-v1",
        )

    def _dataset(self):
        return {"families": FAMILIES, "stores": self.stores, "menus": self.menus}

    @staticmethod
    def _query(text, *, query_type="sensory_without_menu"):
        gold = {
            "familyIds": ["family-target"], "menuIds": ["menu-target"],
            "storeIds": ["store-target"], "negative": False,
            "forbiddenMenuIds": [], "forbiddenStoreIds": [],
        }
        return {
            "id": "query-hybrid", "type": query_type, "text": text,
            "filters": {}, "sort": "RECOMMENDED", "gold": gold,
            "acceptableGold": dict(gold),
        }

    @staticmethod
    def _structured_call(query, *, menu_family):
        ranking = ["store-target"] if menu_family else ["store-distractor"]
        evidence = extract_structured_food_evidence(
            query["text"], families=FAMILIES,
            interpretation="MATCHABLE", llm_concepts=[],
        )
        evidence_json = {
            "rawFoodSpans": list(evidence.raw_food_spans),
            "menuFamilies": list(evidence.menu_families) if menu_family else [],
            "familyIds": list(evidence.family_ids) if menu_family else [],
            "ingredients": list(evidence.ingredients),
            "tastes": list(evidence.tastes),
            "broths": list(evidence.broths),
            "methods": list(evidence.methods),
            "forms": list(evidence.forms),
            "sources": {key: value.value for key, value in evidence.sources.items()},
        }
        cutoff = {
            "rankedStoreIds": ranking,
            "strictHit": menu_family, "acceptableHit": menu_family,
            "strictRecall": float(menu_family), "acceptableRecall": float(menu_family),
            "strictFalsePositive": not menu_family,
            "acceptableFalsePositive": not menu_family,
            "ranking": {
                "recall@1": float(menu_family), "ndcg@1": float(menu_family),
                "recall@3": float(menu_family), "ndcg@3": float(menu_family),
                "recall@5": float(menu_family), "ndcg@5": float(menu_family),
                "recall@8": float(menu_family), "ndcg@8": float(menu_family),
                "mrr": float(menu_family),
            },
        }
        return {
            "queryId": query["id"], "repeatIndex": 0,
            "queryType": query["type"], "querySubtype": None,
            "goldNegative": False, "evidence": evidence_json,
            "variants": {
                "C": {
                    "cutoffs": {label: dict(cutoff) for label in ("@1", "@3", "@5", "@8", "@20", "@50", "all")},
                    "safety": {
                        "closedOrForbiddenStoreLeak": False,
                        "privateOrHistoricalMenuLeak": False,
                        "filterViolation": False,
                    },
                },
            },
        }


if __name__ == "__main__":
    unittest.main()
