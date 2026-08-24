import json
import unittest

from miriyum_search_eval.catalog import DatasetConfig, generate_dataset
from miriyum_search_eval.validation import validate_dataset


class DatasetGenerationTest(unittest.TestCase):
    def setUp(self):
        self.config = DatasetConfig(seed=20260823, schema_version="miriyum-search-eval-v2.1")

    def test_generates_exact_reproducible_scale_and_distribution(self):
        first = generate_dataset(self.config)
        second = generate_dataset(self.config)

        self.assertEqual(first, second)
        self.assertEqual(len(first["stores"]), 500)
        self.assertEqual(len(first["menus"]), 5_000)
        self.assertEqual(len(first["families"]), 300)
        self.assertEqual(len({menu["name"] for menu in first["menus"]}), 1_200)
        self.assertEqual(len(first["queries"]), 2_000)
        counts = {}
        for query in first["queries"]:
            counts[query["type"]] = counts.get(query["type"], 0) + 1
        self.assertEqual(
            counts,
            {
                "sensory_without_menu": 800,
                "composite_filter": 400,
                "alias_bidirectional": 300,
                "same_menu_ranking": 200,
                "negative_or_ambiguous_voice": 150,
                "filter_defense": 150,
            },
        )
        voice_counts = {}
        for query in first["queries"]:
            if query["type"] == "negative_or_ambiguous_voice":
                voice_counts[query["subtype"]] = voice_counts.get(query["subtype"], 0) + 1
        self.assertEqual(voice_counts, {"ambiguous_food": 75, "true_no_answer": 75})
        self.assertTrue(all(query["gold"]["negative"] for query in first["queries"] if query.get("subtype") == "true_no_answer"))
        self.assertTrue(all(not query["gold"]["negative"] for query in first["queries"] if query.get("subtype") == "ambiguous_food"))

    def test_validation_reports_no_orphans_duplicates_contradictions_or_leakage(self):
        dataset = generate_dataset(self.config)

        report = validate_dataset(dataset)

        self.assertEqual(report["errors"], [])
        self.assertEqual(report["duplicateIds"], 0)
        self.assertEqual(report["orphanGoldReferences"], 0)
        self.assertEqual(report["contradictoryGoldLabels"], 0)
        self.assertEqual(report["targetLeakageFindings"], 0)
        self.assertEqual(report["queriesWithMissingEligibleGold"], 0)
        self.assertGreater(report["storeRatios"]["closed"], 0)
        self.assertGreater(report["menuVersionRatios"]["historical"], 0)
        self.assertGreater(report["menuVersionRatios"]["private"], 0)

    def test_manifest_records_seed_schema_and_reviewable_gold_provenance(self):
        dataset = generate_dataset(self.config)
        query = dataset["queries"][0]

        self.assertEqual(dataset["metadata"]["seed"], 20260823)
        self.assertEqual(dataset["metadata"]["schemaVersion"], "miriyum-search-eval-v2.1")
        self.assertNotIn("targetMenuName", query["text"])
        self.assertTrue(query["gold"]["provenance"])
        self.assertTrue(query["acceptableGold"]["provenance"])
        json.dumps(dataset, ensure_ascii=False)

    def test_sensory_queries_have_reviewable_multi_gold_from_exact_core_attributes(self):
        dataset = generate_dataset(self.config)
        family_by_id = {family["id"]: family for family in dataset["families"]}
        sensory = [query for query in dataset["queries"] if query["type"] == "sensory_without_menu"]

        self.assertTrue(all(len(query["gold"]["familyIds"]) == 1 for query in sensory))
        self.assertTrue(any(len(query["acceptableGold"]["familyIds"]) > 1 for query in sensory))
        self.assertLessEqual(max(len(query["acceptableGold"]["familyIds"]) for query in sensory), 5)
        for query in sensory:
            source = family_by_id[query["gold"]["familyIds"][0]]["attributes"]
            for family_id in query["acceptableGold"]["familyIds"]:
                candidate = family_by_id[family_id]["attributes"]
                self.assertEqual(
                    tuple(candidate[key] for key in ("ingredient", "taste", "method", "broth")),
                    tuple(source[key] for key in ("ingredient", "taste", "method", "broth")),
                )
            self.assertEqual(query["acceptableGold"]["policy"], "sensory-core-attributes-v1")

    def test_non_sensory_acceptable_gold_preserves_strict_family_and_store_sets(self):
        dataset = generate_dataset(self.config)
        for query in dataset["queries"]:
            if query["type"] == "sensory_without_menu":
                continue
            self.assertEqual(query["acceptableGold"]["familyIds"], query["gold"]["familyIds"])
            self.assertEqual(query["acceptableGold"]["storeIds"], query["gold"]["storeIds"])
            self.assertEqual(query["acceptableGold"]["policy"], "strict-gold-equivalent-v1")


if __name__ == "__main__":
    unittest.main()
