from __future__ import annotations

from collections import Counter
from typing import Any


EXPECTED_QUERY_COUNTS = {
    "sensory_without_menu": 800,
    "composite_filter": 400,
    "alias_bidirectional": 300,
    "same_menu_ranking": 200,
    "negative_or_ambiguous_voice": 150,
    "filter_defense": 150,
}


def _duplicates(values: list[str]) -> int:
    return sum(count - 1 for count in Counter(values).values() if count > 1)


def validate_dataset(dataset: dict[str, Any]) -> dict[str, Any]:
    stores = dataset["stores"]
    menus = dataset["menus"]
    families = dataset["families"]
    queries = dataset["queries"]
    errors: list[str] = []
    expected_sizes = {"stores": 500, "menus": 5_000, "families": 300, "queries": 2_000}
    for key, expected in expected_sizes.items():
        if len(dataset[key]) != expected:
            errors.append(f"{key}: expected {expected}, got {len(dataset[key])}")
    query_counts = Counter(query["type"] for query in queries)
    if dict(query_counts) != EXPECTED_QUERY_COUNTS:
        errors.append(f"query distribution mismatch: {dict(query_counts)}")
    if len({menu["name"] for menu in menus}) != 1_200:
        errors.append("menu variant count must be exactly 1200")
    duplicate_ids = sum((
        _duplicates([store["id"] for store in stores]),
        _duplicates([menu["id"] for menu in menus]),
        _duplicates([family["id"] for family in families]),
        _duplicates([query["id"] for query in queries]),
    ))
    if duplicate_ids:
        errors.append(f"duplicate IDs: {duplicate_ids}")
    store_ids = {store["id"] for store in stores}
    menu_ids = {menu["id"] for menu in menus}
    family_ids = {family["id"] for family in families}
    orphan_gold = 0
    contradictions = 0
    leakage = 0
    missing_eligible = 0
    for query in queries:
        for gold_name in ("gold", "acceptableGold"):
            gold = query.get(gold_name)
            if gold is None:
                errors.append(f"{query['id']}: missing {gold_name}")
                continue
            orphan_gold += len(set(gold["storeIds"]) - store_ids)
            orphan_gold += len(set(gold["menuIds"]) - menu_ids)
            orphan_gold += len(set(gold["familyIds"]) - family_ids)
            if gold["negative"] and (gold["storeIds"] or gold["menuIds"]):
                contradictions += 1
            if not gold["negative"] and not gold["storeIds"]:
                missing_eligible += 1
        gold = query["gold"]
        lowered = query["text"].lower()
        if query["id"].lower() in lowered or any(menu_id.lower() in lowered for menu_id in gold["menuIds"]):
            leakage += 1
    for value, label in ((orphan_gold, "orphan gold"), (contradictions, "contradictory gold"), (leakage, "target leakage"), (missing_eligible, "missing eligible gold")):
        if value:
            errors.append(f"{label}: {value}")
    historical = sum(any(version["status"] == "SUPERSEDED" for version in menu["versions"]) for menu in menus)
    private = sum(any(version["visibility"] == "PRIVATE" for version in menu["versions"]) for menu in menus)
    return {
        "errors": errors,
        "duplicateIds": duplicate_ids,
        "orphanGoldReferences": orphan_gold,
        "contradictoryGoldLabels": contradictions,
        "targetLeakageFindings": leakage,
        "queriesWithMissingEligibleGold": missing_eligible,
        "queryTypeCounts": dict(query_counts),
        "storeRatios": {
            "closed": sum(store["operationStatus"] == "CLOSED" for store in stores) / len(stores),
            "unapproved": sum(store["verificationStatus"] != "APPROVED" for store in stores) / len(stores),
        },
        "menuVersionRatios": {"historical": historical / len(menus), "private": private / len(menus)},
    }
