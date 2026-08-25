from __future__ import annotations

from collections import defaultdict
import json
import math
from statistics import mean
from typing import Any

from .matching import (
    MatchMode,
    PreparedCatalog,
    compact,
    deterministic_remaining_keyword,
    merge_application_candidates,
    retrieve_candidates,
    retrieve_evidence_candidates,
    retrieve_original_candidates,
)
from .metrics import ranking_metrics, stability_metrics, wilson_interval


ATTRIBUTE_DIMENSIONS = ("ingredient", "taste", "method", "broth", "aroma", "texture")
APPLICATION_CUTOFFS: dict[str, int | None] = {"@8": 8, "@20": 20, "@50": 50, "all": None}


def _semantic_hit(dataset: dict[str, Any], query: dict[str, Any], concepts: list[str]) -> bool:
    if query["gold"]["negative"]:
        return not concepts
    family_by_id = {family["id"]: family for family in dataset["families"]}
    expected: set[str] = {compact(value) for value in query.get("expectedConcepts", [])}
    for family_id in query["gold"]["familyIds"]:
        family = family_by_id[family_id]
        expected.update(compact(value) for value in [family["canonical"], family["baseName"], *family["aliases"], *family["variants"]])
        expected.update(compact(str(value)) for value in family["attributes"].values())
    for concept in concepts:
        normalized = compact(concept)
        if len(normalized) < 2:
            continue
        if any(normalized == value or normalized in value or value in normalized for value in expected if len(value) >= 2):
            return True
    return False


def _family_semantic_hit(
    dataset: dict[str, Any], gold: dict[str, Any], concepts: list[str],
) -> bool:
    if gold["negative"]:
        return not concepts
    family_by_id = {family["id"]: family for family in dataset["families"]}
    terms: set[str] = set()
    for family_id in gold["familyIds"]:
        family = family_by_id[family_id]
        terms.update(
            compact(value) for value in (
                family["canonical"], family["baseName"],
                *family["aliases"], *family["variants"],
            ) if compact(value)
        )
    for concept in concepts:
        normalized = compact(concept)
        if not normalized:
            continue
        if any(normalized == term or (len(term) >= 2 and term in normalized) for term in terms):
            return True
    return False


def _attribute_understanding(
    dataset: dict[str, Any], query: dict[str, Any], concepts: list[str],
) -> dict[str, bool]:
    family_by_id = {family["id"]: family for family in dataset["families"]}
    expected = {
        dimension: {
            compact(str(family_by_id[family_id]["attributes"][dimension]))
            for family_id in query["gold"]["familyIds"]
        }
        for dimension in ATTRIBUTE_DIMENSIONS
    }
    normalized_concepts = [compact(concept) for concept in concepts if compact(concept)]
    return {
        dimension: any(
            value == concept or value in concept or concept in value
            for value in values if value
            for concept in normalized_concepts
        )
        for dimension, values in expected.items()
    }


def _gold_hit(ranked: tuple[str, ...], gold: dict[str, Any]) -> bool:
    return not ranked if gold["negative"] else bool(set(ranked) & set(gold["storeIds"]))


def _gold_recall(ranked: tuple[str, ...], gold: dict[str, Any]) -> float:
    relevant = set(gold["storeIds"])
    return len(set(ranked) & relevant) / len(relevant) if relevant else float(not ranked)


def evaluate_call(
    dataset: dict[str, Any], query: dict[str, Any], record: dict[str, Any],
    *, retrieval_cache: dict[tuple[Any, ...], Any] | None = None,
    prepared_catalog: PreparedCatalog | None = None,
    guarded_original_reverse: bool = True,
) -> dict[str, Any]:
    success = record["status"] == "success"
    concepts = record.get("concepts", []) if success else []
    cache_key = (
        tuple(concepts),
        json.dumps(query["filters"], ensure_ascii=False, sort_keys=True, separators=(",", ":")),
        query["sort"],
    )
    retrieval = retrieval_cache.get(cache_key) if retrieval_cache is not None else None
    if retrieval is None:
        retrieval = retrieve_candidates(
            concepts=concepts,
            families=dataset["families"], stores=dataset["stores"], menus=dataset["menus"],
            filters=query["filters"], sort=query["sort"], prepared_catalog=prepared_catalog,
        )
        if retrieval_cache is not None:
            retrieval_cache[cache_key] = retrieval
    remaining_keyword = deterministic_remaining_keyword(query)
    original_cache_key = (
        "__original__", query["id"], remaining_keyword, guarded_original_reverse,
    )
    original = retrieval_cache.get(original_cache_key) if retrieval_cache is not None else None
    if original is None:
        original = retrieve_original_candidates(
            remaining_keyword=remaining_keyword, stores=dataset["stores"],
            menus=dataset["menus"], filters=query["filters"], sort=query["sort"],
            guarded_reverse=guarded_original_reverse,
        )
        if retrieval_cache is not None:
            retrieval_cache[original_cache_key] = original
    evidence_cache_key = (
        "__evidence__", query["id"], tuple(concepts),
        json.dumps(query["filters"], ensure_ascii=False, sort_keys=True, separators=(",", ":")),
        query["sort"],
    )
    evidence = retrieval_cache.get(evidence_cache_key) if retrieval_cache is not None else None
    if evidence is None:
        evidence = retrieve_evidence_candidates(
            query_text=query["text"], concepts=concepts,
            families=dataset["families"], stores=dataset["stores"], menus=dataset["menus"],
            filters=query["filters"], sort=query["sort"], prepared_catalog=prepared_catalog,
            name_result=retrieval,
        )
        if retrieval_cache is not None:
            retrieval_cache[evidence_cache_key] = evidence
    acceptable_gold = query.get("acceptableGold", query["gold"])
    gold_stores = query["gold"]["storeIds"]
    gold_store_set = set(gold_stores)
    negative = query["gold"]["negative"]
    one_way_hit = not retrieval.one_way_store_ids if negative else bool(set(retrieval.one_way_store_ids) & gold_store_set)
    bidirectional_hit = not retrieval.bidirectional_store_ids if negative else bool(set(retrieval.bidirectional_store_ids) & gold_store_set)
    original_hit = not original.store_ids if negative else bool(set(original.store_ids) & gold_store_set)
    supplement_store_ids = retrieval.bidirectional_store_ids if success else ()
    final_by_cutoff: dict[str, dict[str, Any]] = {}
    for label, page_size in APPLICATION_CUTOFFS.items():
        ranked = merge_application_candidates(
            original_store_ids=original.store_ids,
            supplement_store_ids=supplement_store_ids,
            page_size=page_size,
        )
        final_by_cutoff[label] = {
            "rankedStoreIds": list(ranked),
            "strictHit": _gold_hit(ranked, query["gold"]),
            "acceptableHit": _gold_hit(ranked, acceptable_gold),
            "strictRecall": _gold_recall(ranked, query["gold"]),
            "acceptableRecall": _gold_recall(ranked, acceptable_gold),
        }
    simulated_evidence_by_cutoff: dict[str, dict[str, Any]] = {}
    for label, page_size in APPLICATION_CUTOFFS.items():
        ranked = merge_application_candidates(
            original_store_ids=original.store_ids,
            supplement_store_ids=evidence.store_ids,
            page_size=page_size,
        )
        simulated_evidence_by_cutoff[label] = {
            "rankedStoreIds": list(ranked),
            "strictHit": _gold_hit(ranked, query["gold"]),
            "acceptableHit": _gold_hit(ranked, acceptable_gold),
            "strictRecall": _gold_recall(ranked, query["gold"]),
            "acceptableRecall": _gold_recall(ranked, acceptable_gold),
            "strictFalsePositive": bool(ranked) if query["gold"]["negative"] else bool(ranked and not _gold_hit(ranked, query["gold"])),
            "acceptableFalsePositive": bool(ranked) if acceptable_gold["negative"] else bool(ranked and not _gold_hit(ranked, acceptable_gold)),
        }
    final_store_ids = tuple(final_by_cutoff["@8"]["rankedStoreIds"])
    final_hit = not final_store_ids if negative else bool(set(final_store_ids) & gold_store_set)
    final_false_positive = bool(final_store_ids) if negative else bool(final_store_ids and not (set(final_store_ids) & gold_store_set))
    menu_by_id = {menu["id"]: menu for menu in dataset["menus"]}
    store_by_id = {store["id"]: store for store in dataset["stores"]}
    gold_families = set(query["gold"]["familyIds"])
    match_counts = {mode.value: 0 for mode in MatchMode}
    false_positive_counts = {mode.value: 0 for mode in MatchMode}
    for menu_id, modes in retrieval.matched_modes.items():
        for mode in modes:
            match_counts[mode.value] += 1
            if menu_by_id[menu_id]["familyId"] not in gold_families:
                false_positive_counts[mode.value] += 1
    forbidden_menu_ids = set(query["gold"].get("forbiddenMenuIds", []))
    forbidden_store_ids = set(query["gold"].get("forbiddenStoreIds", []))
    returned_menu_ids = set(retrieval.bidirectional_menu_ids)
    returned_store_ids = set(retrieval.bidirectional_store_ids)
    closed_leak = any(store_by_id[store_id]["operationStatus"] == "CLOSED" for store_id in returned_store_ids)
    private_or_historical_leak = bool(returned_menu_ids & forbidden_menu_ids)
    region_filter_violation = False
    ambience_filter_violation = False
    for store_id in returned_store_ids:
        store = store_by_id[store_id]
        if query["filters"].get("region") and store["region"] != query["filters"]["region"]:
            region_filter_violation = True
        if query["filters"].get("ambience") and store["ambience"] != query["filters"]["ambience"]:
            ambience_filter_violation = True
    price_filter_violation = any(
        query["filters"].get("maxPrice") is not None
        and menu_by_id[menu_id]["price"] > query["filters"]["maxPrice"]
        for menu_id in returned_menu_ids
    )
    category_filter_violation = any(
        query["filters"].get("category")
        and menu_by_id[menu_id]["category"] != query["filters"]["category"]
        for menu_id in returned_menu_ids
    )
    filter_violation = any((
        region_filter_violation, ambience_filter_violation,
        price_filter_violation, category_filter_violation,
    ))
    if returned_store_ids & forbidden_store_ids:
        closed_leak = True
    result = {
        **record,
        "schemaVersion": "miriyum-search-call-evaluation-v2.1",
        "queryType": query["type"],
        "querySubtype": query.get("subtype"),
        "goldNegative": negative,
        "providerFormatSuccess": success,
        "semanticHit": success and _semantic_hit(dataset, query, concepts),
        "strictFamilySemanticHit": success and _family_semantic_hit(dataset, query["gold"], concepts),
        "acceptableFamilySemanticHit": success and _family_semantic_hit(dataset, acceptable_gold, concepts),
        "attributeUnderstanding": _attribute_understanding(dataset, query, concepts) if success else {
            dimension: False for dimension in ATTRIBUTE_DIMENSIONS
        },
        "oneWayDbCompatibleHit": success and one_way_hit,
        "bidirectionalMenuNameHit": success and bidirectional_hit,
        "actualApplicationPredicate": {"variant": "bidirectional-current", "hit": success and bidirectional_hit},
        "legacyOneWayCounterfactual": {"variant": "pre-issue-589-one-way", "hit": success and one_way_hit},
        "originalSearch": {
            "variant": (
                "whole-keyword-plus-most-specific-current-published-menu-name"
                if guarded_original_reverse
                else "pre-issue-616-whole-remaining-keyword"
            ),
            "remainingKeyword": remaining_keyword,
            "hit": original_hit,
            "rankedStoreIds": list(original.store_ids[:8]),
        },
        "supplementSearch": {
            "variant": "llm-bidirectional-current",
            "hit": success and bidirectional_hit,
            "rankedStoreIds": list(retrieval.bidirectional_store_ids[:8]),
        },
        "simulatedEvidenceSearch": {
            "variant": "simulated-query-attribute-evidence-v1",
            "actualApplication": False,
            "strictPoolHit": _gold_hit(evidence.store_ids, query["gold"]),
            "acceptablePoolHit": _gold_hit(evidence.store_ids, acceptable_gold),
            "rankedStoreIds": list(evidence.store_ids),
        },
        "finalApplication": {
            "variant": "original-then-llm-supplement-synthetic-proxy",
            "hit": final_hit,
            "rankedStoreIds": list(final_store_ids),
            "falsePositive": final_false_positive,
        },
        "finalApplicationByCutoff": final_by_cutoff,
        "simulatedEvidenceByCutoff": simulated_evidence_by_cutoff,
        "rankedStoreIds": list(final_store_ids),
        "ranking": ranking_metrics(list(final_store_ids), gold_stores),
        "matchCounts": match_counts,
        "falsePositiveCounts": false_positive_counts,
        "falsePositive": final_false_positive,
        "closedLeak": closed_leak,
        "privateOrHistoricalLeak": private_or_historical_leak,
        "filterViolation": filter_violation,
        "regionFilterViolation": region_filter_violation,
        "priceFilterViolation": price_filter_violation,
        "categoryFilterViolation": category_filter_violation,
        "ambienceFilterViolation": ambience_filter_violation,
    }
    return result


def _percentile(values: list[float], percentile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    position = (len(ordered) - 1) * percentile
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def _binary_summary(successes: int, total: int) -> dict[str, Any]:
    low, high = wilson_interval(successes, total) if total else (0.0, 0.0)
    return {"successes": successes, "total": total, "rate": successes / total if total else 0.0, "wilson95": {"low": low, "high": high}}


def derive_call_diagnostics(calls: list[dict[str, Any]]) -> dict[str, Any]:
    by_query: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for call in calls:
        by_query[call["queryId"]].append(call)
    gains = regressions = 0
    for query_calls in by_query.values():
        threshold = len(query_calls) // 2 + 1
        one_way = sum(bool(call["oneWayDbCompatibleHit"]) for call in query_calls) >= threshold
        bidirectional = sum(bool(call["bidirectionalMenuNameHit"]) for call in query_calls) >= threshold
        gains += bidirectional and not one_way
        regressions += one_way and not bidirectional
    answerable = [call for call in calls if not call.get("goldNegative")]
    answerable_abstentions = sum(
        call.get("interpretation") in {"AMBIGUOUS", "NO_FOOD_SIGNAL"}
        for call in answerable
    )
    user_visible_answerable_abstentions = sum(
        call.get("interpretation") in {"AMBIGUOUS", "NO_FOOD_SIGNAL"}
        and not call.get("finalApplication", {}).get("hit", False)
        for call in answerable
    )
    no_answer = [
        call for call in calls
        if call.get("goldNegative") and call.get("querySubtype") == "true_no_answer"
    ]
    return {
        "uniqueBidirectionalGains": gains,
        "uniqueBidirectionalRegressions": regressions,
        "answerableCalls": len(answerable),
        "answerableAbstentions": answerable_abstentions,
        "answerableAbstentionRate": answerable_abstentions / len(answerable) if answerable else 0.0,
        "userVisibleAnswerableAbstentions": user_visible_answerable_abstentions,
        "userVisibleAnswerableAbstentionRate": user_visible_answerable_abstentions / len(answerable) if answerable else 0.0,
        "trueNoAnswerCalls": len(no_answer),
        "trueNoAnswerFalsePositives": sum(bool(call.get("falsePositive")) for call in no_answer),
    }


def aggregate_evaluation(calls: list[dict[str, Any]]) -> dict[str, Any]:
    by_query: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for call in calls:
        by_query[call["queryId"]].append(call)
    unique_success: dict[str, int] = {
        "providerFormat": 0, "semantic": 0,
        "strictFamilySemantic": 0, "acceptableFamilySemantic": 0,
        "oneWay": 0, "bidirectional": 0, "original": 0,
        "finalApplication": 0,
        **{
            f"{gold}Final{cutoff}": 0
            for gold in ("strict", "acceptable")
            for cutoff in ("@8", "@20", "@50", "All")
        },
        **{
            f"simulated{gold.title()}Final{cutoff}": 0
            for gold in ("strict", "acceptable")
            for cutoff in ("@8", "@20", "@50", "All")
        },
    }
    stability_rows = []
    for query_calls in by_query.values():
        threshold = len(query_calls) // 2 + 1
        fields = {
            "providerFormat": "providerFormatSuccess",
            "semantic": "semanticHit",
            "strictFamilySemantic": "strictFamilySemanticHit",
            "acceptableFamilySemantic": "acceptableFamilySemanticHit",
            "oneWay": "oneWayDbCompatibleHit",
            "bidirectional": "bidirectionalMenuNameHit",
            "original": "originalSearch",
            "finalApplication": "finalApplication",
        }
        for key, field in fields.items():
            values = [
                call.get(field, call.get("semanticHit", False))
                if field in {"strictFamilySemanticHit", "acceptableFamilySemanticHit"}
                else call.get(field, False)
                for call in query_calls
            ]
            unique_success[key] += sum(
                bool(value.get("hit") if isinstance(value, dict) else value)
                for value in values
            ) >= threshold
        for label in APPLICATION_CUTOFFS:
            suffix = "All" if label == "all" else label
            for gold in ("strict", "acceptable"):
                values = [
                    bool(call.get("finalApplicationByCutoff", {}).get(label, {}).get(
                        f"{gold}Hit",
                        call.get("finalApplication", {}).get("hit", False) if label == "@8" else False,
                    ))
                    for call in query_calls
                ]
                unique_success[f"{gold}Final{suffix}"] += sum(values) >= threshold
                simulated_values = [
                    bool(call.get("simulatedEvidenceByCutoff", {}).get(label, {}).get(f"{gold}Hit", False))
                    for call in query_calls
                ]
                unique_success[f"simulated{gold.title()}Final{suffix}"] += sum(simulated_values) >= threshold
        stability_rows.append(stability_metrics([call["rankedStoreIds"] for call in sorted(query_calls, key=lambda value: value["repeatIndex"])]))
    latencies = [float(call["latencyMs"]) for call in calls]
    ranking_keys = ("recall@1", "recall@3", "recall@5", "recall@8", "mrr", "ndcg@1", "ndcg@3", "ndcg@5", "ndcg@8")
    type_breakdown: dict[str, dict[str, Any]] = {}
    for query_type in sorted({call["queryType"] for call in calls}):
        subset = [call for call in calls if call["queryType"] == query_type]
        type_breakdown[query_type] = {
            "calls": len(subset),
            "providerFormatRate": mean(bool(call["providerFormatSuccess"]) for call in subset),
            "semanticRate": mean(bool(call["semanticHit"]) for call in subset),
            "strictFamilySemanticRate": mean(bool(call.get("strictFamilySemanticHit", call["semanticHit"])) for call in subset),
            "acceptableFamilySemanticRate": mean(bool(call.get("acceptableFamilySemanticHit", call["semanticHit"])) for call in subset),
            "oneWayRate": mean(bool(call["oneWayDbCompatibleHit"]) for call in subset),
            "bidirectionalRate": mean(bool(call["bidirectionalMenuNameHit"]) for call in subset),
            "originalRate": mean(bool(call.get("originalSearch", {}).get("hit")) for call in subset),
            "finalApplicationRate": mean(bool(call.get("finalApplication", {}).get("hit")) for call in subset),
            "falsePositiveRate": mean(bool(call["falsePositive"]) for call in subset),
            "cutoffs": {
                label: {
                    f"{gold}HitRate": mean(bool(call.get("finalApplicationByCutoff", {}).get(label, {}).get(
                        f"{gold}Hit",
                        call.get("finalApplication", {}).get("hit", False) if label == "@8" else False,
                    )) for call in subset)
                    for gold in ("strict", "acceptable")
                }
                for label in APPLICATION_CUTOFFS
            },
            "simulatedEvidenceCutoffs": {
                label: {
                    f"{gold}HitRate": mean(bool(
                        call.get("simulatedEvidenceByCutoff", {}).get(label, {}).get(f"{gold}Hit", False)
                    ) for call in subset)
                    for gold in ("strict", "acceptable")
                }
                for label in APPLICATION_CUTOFFS
            },
        }
    match_totals = {mode.value: sum(call["matchCounts"][mode.value] for call in calls) for mode in MatchMode}
    false_positive_totals = {mode.value: sum(call["falsePositiveCounts"][mode.value] for call in calls) for mode in MatchMode}
    interpretation_labels = ("MATCHABLE", "AMBIGUOUS", "NO_FOOD_SIGNAL")
    interpretation_counts = {
        label: sum(call.get("interpretation") == label for call in calls)
        for label in interpretation_labels
    }
    interpretation_by_subtype: dict[str, dict[str, int]] = {}
    for subtype in sorted({str(call.get("querySubtype") or "unspecified") for call in calls}):
        subset = [call for call in calls if str(call.get("querySubtype") or "unspecified") == subtype]
        interpretation_by_subtype[subtype] = {
            label: sum(call.get("interpretation") == label for call in subset)
            for label in interpretation_labels
        }
    unique_total = len(by_query)
    sensory_by_query = {
        query_id: query_calls for query_id, query_calls in by_query.items()
        if query_calls[0].get("queryType") == "sensory_without_menu"
    }
    attribute_unique: dict[str, int] = {dimension: 0 for dimension in ATTRIBUTE_DIMENSIONS}
    for query_calls in sensory_by_query.values():
        threshold = len(query_calls) // 2 + 1
        for dimension in ATTRIBUTE_DIMENSIONS:
            attribute_unique[dimension] += sum(
                bool(call.get("attributeUnderstanding", {}).get(dimension, False))
                for call in query_calls
            ) >= threshold
    sensory_calls = [call for call in calls if call.get("queryType") == "sensory_without_menu"]
    candidate_cutoffs = {
        label: {
            **{
                f"{gold}HitRate": mean(
                    bool(call.get("finalApplicationByCutoff", {}).get(label, {}).get(
                        f"{gold}Hit",
                        call.get("finalApplication", {}).get("hit", False) if label == "@8" else False,
                    )) for call in calls
                ) if calls else 0.0
                for gold in ("strict", "acceptable")
            },
            **{
                f"{gold}Recall": mean(
                    float(call.get("finalApplicationByCutoff", {}).get(label, {}).get(f"{gold}Recall", 0.0))
                    for call in calls
                ) if calls else 0.0
                for gold in ("strict", "acceptable")
            },
        }
        for label in APPLICATION_CUTOFFS
    }
    simulated_evidence_cutoffs = {
        label: {
            **{
                f"{gold}HitRate": mean(bool(
                    call.get("simulatedEvidenceByCutoff", {}).get(label, {}).get(f"{gold}Hit", False)
                ) for call in calls) if calls else 0.0
                for gold in ("strict", "acceptable")
            },
            **{
                f"{gold}Recall": mean(float(
                    call.get("simulatedEvidenceByCutoff", {}).get(label, {}).get(f"{gold}Recall", 0.0)
                ) for call in calls) if calls else 0.0
                for gold in ("strict", "acceptable")
            },
        }
        for label in APPLICATION_CUTOFFS
    }
    query_type_unique: dict[str, dict[str, Any]] = {}
    for query_type in sorted({call.get("queryType") for call in calls}):
        grouped = [
            query_calls for query_calls in by_query.values()
            if query_calls[0].get("queryType") == query_type
        ]
        counts = {
            "strictFamilySemantic": 0,
            "acceptableFamilySemantic": 0,
            **{
                f"{gold}Final{cutoff}": 0
                for gold in ("strict", "acceptable")
                for cutoff in ("@8", "@20", "@50", "All")
            },
            **{
                f"simulated{gold.title()}Final{cutoff}": 0
                for gold in ("strict", "acceptable")
                for cutoff in ("@8", "@20", "@50", "All")
            },
        }
        for query_calls in grouped:
            threshold = len(query_calls) // 2 + 1
            counts["strictFamilySemantic"] += sum(
                bool(call.get("strictFamilySemanticHit", call.get("semanticHit", False)))
                for call in query_calls
            ) >= threshold
            counts["acceptableFamilySemantic"] += sum(
                bool(call.get("acceptableFamilySemanticHit", call.get("semanticHit", False)))
                for call in query_calls
            ) >= threshold
            for label in APPLICATION_CUTOFFS:
                suffix = "All" if label == "all" else label
                for gold in ("strict", "acceptable"):
                    counts[f"{gold}Final{suffix}"] += sum(
                        bool(call.get("finalApplicationByCutoff", {}).get(label, {}).get(
                            f"{gold}Hit",
                            call.get("finalApplication", {}).get("hit", False) if label == "@8" else False,
                        ))
                        for call in query_calls
                    ) >= threshold
                    counts[f"simulated{gold.title()}Final{suffix}"] += sum(
                        bool(call.get("simulatedEvidenceByCutoff", {}).get(label, {}).get(f"{gold}Hit", False))
                        for call in query_calls
                    ) >= threshold
        query_type_unique[str(query_type)] = {
            key: _binary_summary(value, len(grouped)) for key, value in counts.items()
        }
    return {
        "schemaVersion": "miriyum-search-aggregate-v2.1",
        "statisticalUnit": {"uniqueQueries": unique_total, "calls": len(calls), "note": "Wilson intervals use majority success over unique queries; calls measure repetition stability."},
        "uniqueQuerySuccess": {key: _binary_summary(value, unique_total) for key, value in unique_success.items()},
        "callRates": {
            "providerFormat": mean(bool(call["providerFormatSuccess"]) for call in calls) if calls else 0.0,
            "semantic": mean(bool(call["semanticHit"]) for call in calls) if calls else 0.0,
            "strictFamilySemantic": mean(bool(call.get("strictFamilySemanticHit", call["semanticHit"])) for call in calls) if calls else 0.0,
            "acceptableFamilySemantic": mean(bool(call.get("acceptableFamilySemanticHit", call["semanticHit"])) for call in calls) if calls else 0.0,
            "oneWayDbCompatible": mean(bool(call["oneWayDbCompatibleHit"]) for call in calls) if calls else 0.0,
            "actualBidirectional": mean(bool(call["bidirectionalMenuNameHit"]) for call in calls) if calls else 0.0,
            "originalSearch": mean(bool(call.get("originalSearch", {}).get("hit")) for call in calls) if calls else 0.0,
            "finalApplication": mean(bool(call.get("finalApplication", {}).get("hit")) for call in calls) if calls else 0.0,
        },
        "attributeUnderstanding": {
            "scope": "sensory_without_menu",
            "uniqueQueries": len(sensory_by_query),
            "uniqueQuerySuccess": {
                dimension: _binary_summary(successes, len(sensory_by_query))
                for dimension, successes in attribute_unique.items()
            },
            "callRates": {
                dimension: mean(bool(call.get("attributeUnderstanding", {}).get(dimension, False)) for call in sensory_calls)
                if sensory_calls else 0.0
                for dimension in ATTRIBUTE_DIMENSIONS
            },
        },
        "candidateCutoffs": candidate_cutoffs,
        "simulatedEvidenceCutoffs": simulated_evidence_cutoffs,
        "queryTypeUniqueSuccess": query_type_unique,
        "interpretations": interpretation_counts,
        "abstentionRate": mean(call.get("interpretation") in {"AMBIGUOUS", "NO_FOOD_SIGNAL"} for call in calls) if calls else 0.0,
        "interpretationBySubtype": interpretation_by_subtype,
        "ranking": {key: mean(call["ranking"][key] for call in calls) if calls else 0.0 for key in ranking_keys},
        "matchCounts": match_totals,
        "falsePositiveCounts": false_positive_totals,
        "negativeFalsePositiveRate": mean(
            bool(call["falsePositive"]) for call in calls
            if call["queryType"] == "negative_or_ambiguous_voice" and call.get("goldNegative")
        ) if any(call["queryType"] == "negative_or_ambiguous_voice" and call.get("goldNegative") for call in calls) else 0.0,
        "negativeFalsePositiveDenominator": sum(
            call["queryType"] == "negative_or_ambiguous_voice" and bool(call.get("goldNegative"))
            for call in calls
        ),
        "simulatedEvidenceNegativeFalsePositiveRate": mean(
            bool(call.get("simulatedEvidenceByCutoff", {}).get("@8", {}).get("strictFalsePositive", False))
            for call in calls
            if call["queryType"] == "negative_or_ambiguous_voice" and call.get("goldNegative")
        ) if any(call["queryType"] == "negative_or_ambiguous_voice" and call.get("goldNegative") for call in calls) else 0.0,
        "leakageAndFilters": {
            "closedLeakRate": mean(bool(call["closedLeak"]) for call in calls) if calls else 0.0,
            "privateOrHistoricalLeakRate": mean(bool(call["privateOrHistoricalLeak"]) for call in calls) if calls else 0.0,
            "filterViolationRate": mean(bool(call["filterViolation"]) for call in calls) if calls else 0.0,
            "regionFilterViolationRate": mean(bool(call.get("regionFilterViolation")) for call in calls) if calls else 0.0,
            "priceFilterViolationRate": mean(bool(call.get("priceFilterViolation")) for call in calls) if calls else 0.0,
            "categoryFilterViolationRate": mean(bool(call.get("categoryFilterViolation")) for call in calls) if calls else 0.0,
            "ambienceFilterViolationRate": mean(bool(call.get("ambienceFilterViolation")) for call in calls) if calls else 0.0,
        },
        "stability": {
            "queryCount": len(stability_rows),
            "meanTop1Stability": mean(row["top1Stability"] for row in stability_rows) if stability_rows else 0.0,
            "meanPairwiseJaccard": mean(row["pairwiseJaccard"] for row in stability_rows) if stability_rows else 0.0,
        },
        "latencyMs": {
            "mean": mean(latencies) if latencies else 0.0,
            "p50": _percentile(latencies, 0.50), "p95": _percentile(latencies, 0.95),
            "p99": _percentile(latencies, 0.99), "max": max(latencies, default=0.0),
            "over2SecondsRate": mean(value > 2_000 for value in latencies) if latencies else 0.0,
        },
        "usage": {
            "inputTokens": sum(call["inputTokens"] for call in calls),
            "outputTokens": sum(call["outputTokens"] for call in calls),
            "costUsd": sum(call["costUsd"] for call in calls),
            "retries": sum(call["retryCount"] for call in calls),
            "failedCalls": sum(not call["providerFormatSuccess"] for call in calls),
        },
        "queryTypeBreakdown": type_breakdown,
    }
