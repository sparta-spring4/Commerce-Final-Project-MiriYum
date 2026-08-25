from __future__ import annotations

from collections import Counter
from pathlib import Path
import unittest

from miriyum_search_eval.staging_catalog import (
    dataset_fingerprint,
    generate_staging_dataset,
    staging_pilot_queries,
    validate_staging_dataset,
)


REPO_ROOT = Path(__file__).resolve().parents[3]
SEED_SQL = REPO_ROOT / "backend/scripts/dev-data/search-profile-demo-500-stores.sql"


class StagingCatalogTest(unittest.TestCase):
    def test_generates_exact_staging_scale_distribution_and_public_ids(self):
        dataset = generate_staging_dataset(SEED_SQL, seed=20260825)

        self.assertEqual(dataset["metadata"]["schemaVersion"], "miriyum-staging-search-eval-v1")
        self.assertEqual(dataset["metadata"]["seed"], 20260825)
        self.assertEqual(len(dataset["stores"]), 500)
        self.assertEqual(len(dataset["menus"]), 5_000)
        self.assertEqual(len(dataset["menuTemplates"]), 60)
        self.assertEqual(len(dataset["queries"]), 2_000)
        self.assertEqual(Counter(query["queryType"] for query in dataset["queries"]), {
            "sensory_without_menu": 800,
            "composite_filter": 400,
            "alias_bidirectional": 300,
            "same_menu_ranking": 200,
            "negative_or_ambiguous": 150,
            "generic_or_impossible_defense": 150,
        })
        self.assertEqual(len({query["id"] for query in dataset["queries"]}), 2_000)
        self.assertEqual(len({query["text"] for query in dataset["queries"]}), 2_000)
        self.assertTrue(all(
            isinstance(store_id, str) and store_id.isdecimal() and not store_id.startswith("0")
            for query in dataset["queries"]
            for store_id in query["goldStoreIds"]
        ))
        self.assertTrue(all(
            "8900" not in query["text"] for query in dataset["queries"]
        ))
        composite = [query for query in dataset["queries"] if query["queryType"] == "composite_filter"]
        self.assertTrue(all(set(query["filters"]) == {"region", "categoryCode", "maximumPrice"} for query in composite))
        directional = [query for query in dataset["queries"] if query["queryType"] == "alias_bidirectional"]
        self.assertEqual(Counter(query["matchMode"] for query in directional), {
            "exact": 75, "forward": 75, "reverse": 75, "alias": 75,
        })
        stores = {store["id"]: store for store in dataset["stores"]}
        for query in [value for value in directional if value["matchMode"] == "reverse"]:
            family = query["matchExpression"]
            expected_categories = {
                template["categoryCode"] for template in dataset["menuTemplates"]
                if template["menuFamily"] == family
            }
            self.assertEqual(
                {stores[store_id]["categoryCode"] for store_id in query["goldStoreIds"]},
                expected_categories,
            )
        ranking = [query for query in dataset["queries"] if query["queryType"] == "same_menu_ranking"]
        self.assertTrue(all(len(query["goldStoreIds"]) >= 50 for query in ranking))
        self.assertTrue(all(len(query["expectedBeforeStorePairs"]) == 3 for query in ranking))
        self.assertTrue(all(query["expectedTopStoreId"] in query["goldStoreIds"] for query in ranking))
        validation = validate_staging_dataset(dataset)
        self.assertEqual(validation["errors"], [])
        self.assertTrue(all(value == 0 or value is False for key, value in validation.items() if key != "errors"))

    def test_same_seed_is_byte_stable_and_source_bound(self):
        first = generate_staging_dataset(SEED_SQL, seed=20260825)
        second = generate_staging_dataset(SEED_SQL, seed=20260825)

        self.assertEqual(dataset_fingerprint(first), dataset_fingerprint(second))
        self.assertEqual(
            first["metadata"]["corpusSourceSha256"],
            "5aaf573eb4e09851b30753ed2e4da4669f0c95d2f0ff674471024afff40c37db",
        )
        self.assertEqual(
            first["metadata"]["corpusSource"],
            "backend/scripts/dev-data/search-profile-demo-500-stores.sql",
        )

    def test_pilot_is_deterministic_and_stratified(self):
        dataset = generate_staging_dataset(SEED_SQL, seed=20260825)

        pilot = staging_pilot_queries(dataset["queries"], seed=20260825)

        self.assertEqual(len(pilot), 100)
        self.assertEqual(Counter(query["queryType"] for query in pilot), {
            "sensory_without_menu": 40,
            "composite_filter": 20,
            "alias_bidirectional": 15,
            "same_menu_ranking": 10,
            "negative_or_ambiguous": 8,
            "generic_or_impossible_defense": 7,
        })
        self.assertEqual(
            [query["id"] for query in pilot],
            [query["id"] for query in staging_pilot_queries(dataset["queries"], seed=20260825)],
        )


if __name__ == "__main__":
    unittest.main()
