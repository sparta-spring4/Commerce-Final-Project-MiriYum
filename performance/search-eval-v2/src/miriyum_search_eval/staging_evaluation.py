from __future__ import annotations

from collections import Counter, defaultdict
from itertools import combinations
import math
from typing import Any

from .metrics import ranking_metrics, wilson_interval


CUTOFFS = (1, 3, 5, 8, 20, 50)


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


def _ordered_hit(predicted: list[str], expected: list[str]) -> bool:
    previous = -1
    for store_id in expected:
        try:
            current = predicted.index(store_id)
        except ValueError:
            return False
        if current <= previous:
            return False
        previous = current
    return True


def _pairwise_hits(predicted: list[str], pairs: list[list[str]]) -> tuple[int, int]:
    positions = {store_id: index for index, store_id in enumerate(predicted)}
    hits = 0
    for left, right in pairs:
        if left in positions and right in positions and positions[left] < positions[right]:
            hits += 1
    return hits, len(pairs)


def evaluate_staging_records(
    dataset: dict[str, Any], records: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    queries = {query["id"]: query for query in dataset.get("queries", [])}
    stores = {store["id"]: store for store in dataset.get("stores", [])}
    template_prices = {
        (template.get("categoryCode"), template.get("menuSlot")): template.get("price")
        for template in dataset.get("menuTemplates", [])
    }
    prices_by_store: dict[str, list[int]] = defaultdict(list)
    for menu in dataset.get("menus", []):
        price = template_prices.get((menu.get("categoryCode"), menu.get("menuSlot")))
        if isinstance(price, int):
            prices_by_store[menu["storeId"]].append(price)
    evaluated = []
    for record in records:
        query = queries.get(record.get("queryId"))
        if query is None:
            raise ValueError(f"staging result has unknown query: {record.get('queryId')}")
        success = record.get("status") == "success"
        predicted = list(record.get("rankedStoreIds", [])) if success else []
        relevant = list(query.get("goldStoreIds", []))
        metrics = ranking_metrics(predicted, relevant, CUTOFFS)
        expected_order = query.get("expectedOrderedStoreIds", [])
        expected_top = query.get("expectedTopStoreId")
        pairwise_hits, pairwise_total = _pairwise_hits(
            predicted, query.get("expectedBeforeStorePairs", []),
        )
        ranking = {
            **{f"hitAt{k}": bool(set(predicted[:k]) & set(relevant)) for k in CUTOFFS},
            **{f"recallAt{k}": metrics[f"recall@{k}"] for k in CUTOFFS},
            **{f"ndcgAt{k}": metrics[f"ndcg@{k}"] for k in CUTOFFS},
            "mrr": metrics["mrr"],
            "expectedOrderHit": _ordered_hit(predicted, expected_order) if expected_order else None,
            "expectedTopHit": predicted[:1] == [expected_top] if expected_top else None,
            "pairwiseHits": pairwise_hits,
            "pairwiseComparisons": pairwise_total,
            "pairwiseAccuracy": pairwise_hits / pairwise_total if pairwise_total else None,
            "irrelevantReturnedAt8": sum(store_id not in set(relevant) for store_id in predicted[:8]),
        }
        filters = query.get("filters", {})
        returned_stores = [stores[store_id] for store_id in predicted if store_id in stores]
        region_violation = bool(filters.get("region")) and any(
            store.get("region") != filters["region"] for store in returned_stores
        )
        category_violation = bool(filters.get("categoryCode")) and any(
            store.get("categoryCode") != filters["categoryCode"] for store in returned_stores
        )
        maximum_price = filters.get("maximumPrice")
        price_violation = isinstance(maximum_price, int) and any(
            not any(price <= maximum_price for price in prices_by_store.get(store_id, []))
            for store_id in predicted
        )
        evaluated.append({
            **record,
            "queryType": query.get("queryType"),
            "matchMode": query.get("matchMode"),
            "goldNegative": bool(query.get("goldNegative")),
            "providerOrFormatSuccess": success,
            "ranking": ranking,
            "falsePositive": bool(query.get("goldNegative")) and success and bool(predicted),
            "regionFilterViolation": region_violation,
            "categoryFilterViolation": category_violation,
            "priceFilterViolation": price_violation,
            "regionFilterApplicable": bool(filters.get("region")),
            "categoryFilterApplicable": bool(filters.get("categoryCode")),
            "priceFilterApplicable": isinstance(maximum_price, int),
        })
    return evaluated


def _wilson(successes: int, total: int) -> dict[str, Any]:
    low, high = wilson_interval(successes, total) if total else (0.0, 0.0)
    return {
        "successes": successes, "denominator": total,
        "rate": successes / total if total else 0.0,
        "low": low, "high": high,
    }


def _stability(rows: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[str, dict[int, list[str]]] = defaultdict(dict)
    for row in rows:
        if row.get("status") == "success" and isinstance(row.get("repeatIndex"), int):
            grouped[row["queryId"]][row["repeatIndex"]] = list(row.get("rankedStoreIds", []))
    repeated = [
        [by_repeat[index] for index in range(5)]
        for by_repeat in grouped.values() if set(by_repeat) == set(range(5))
    ]
    stable = 0
    jaccards = []
    for rankings in repeated:
        top1 = [ranking[0] if ranking else None for ranking in rankings]
        stable += len(set(top1)) == 1
        for left, right in combinations(rankings, 2):
            left_set, right_set = set(left), set(right)
            union = left_set | right_set
            jaccards.append(len(left_set & right_set) / len(union) if union else 1.0)
    return {
        "queriesWithRepeats": len(repeated),
        "definition": "exactly-five-successful-distinct-repeat-indexes",
        "top1StableQueries": stable,
        "top1StableRate": stable / len(repeated) if repeated else 0.0,
        "meanPairwiseJaccard": sum(jaccards) / len(jaccards) if jaccards else 0.0,
    }


def aggregate_staging_evaluation(rows: list[dict[str, Any]]) -> dict[str, Any]:
    primary = [row for row in rows if row.get("repeatIndex") == 0]
    total = len(primary)
    positive = [row for row in primary if not row.get("goldNegative")]
    negative = [row for row in primary if row.get("queryType") in {
        "negative_or_ambiguous", "generic_or_impossible_defense",
    }]
    latencies = [float(row.get("latencyMs", 0.0)) for row in rows]
    failure_breakdown = Counter(
        row.get("failureKind") or "unknown" for row in rows
        if not row.get("providerOrFormatSuccess")
    )
    ranking: dict[str, Any] = {"queries": len(positive)}
    wilson: dict[str, Any] = {}
    for cutoff in CUTOFFS:
        hits = sum(bool(row["ranking"][f"hitAt{cutoff}"]) for row in positive)
        ranking[f"hitsAt{cutoff}"] = hits
        ranking[f"hitRateAt{cutoff}"] = hits / len(positive) if positive else 0.0
        ranking[f"meanRecallAt{cutoff}"] = (
            sum(float(row["ranking"][f"recallAt{cutoff}"]) for row in positive) / len(positive) if positive else 0.0
        )
        ranking[f"meanNdcgAt{cutoff}"] = (
            sum(float(row["ranking"][f"ndcgAt{cutoff}"]) for row in positive) / len(positive) if positive else 0.0
        )
        overall_correct = hits + sum(
            row.get("goldNegative") and row.get("providerOrFormatSuccess") and not row.get("falsePositive")
            for row in primary
        )
        wilson[f"positiveHitAt{cutoff}"] = _wilson(hits, len(positive))
        wilson[f"overallCorrectAt{cutoff}"] = _wilson(overall_correct, total)
    ranking["meanMrr"] = (
        sum(float(row["ranking"]["mrr"]) for row in positive) / len(positive) if positive else 0.0
    )
    ordered = [row for row in primary if row["ranking"].get("expectedOrderHit") is not None]
    ranking["expectedOrderQueries"] = len(ordered)
    ranking["expectedOrderHits"] = sum(bool(row["ranking"]["expectedOrderHit"]) for row in ordered)
    top_ordered = [row for row in primary if row["ranking"].get("expectedTopHit") is not None]
    ranking["expectedTopQueries"] = len(top_ordered)
    ranking["expectedTopHits"] = sum(bool(row["ranking"]["expectedTopHit"]) for row in top_ordered)
    pairwise_total = sum(row["ranking"]["pairwiseComparisons"] for row in primary)
    pairwise_hits = sum(row["ranking"]["pairwiseHits"] for row in primary)
    ranking["pairwiseComparisons"] = pairwise_total
    ranking["pairwiseHits"] = pairwise_hits
    ranking["pairwiseAccuracy"] = pairwise_hits / pairwise_total if pairwise_total else 0.0

    by_type = {}
    for query_type in sorted({row.get("queryType") for row in primary if row.get("queryType")}):
        typed = [row for row in primary if row.get("queryType") == query_type]
        by_type[query_type] = {
            "queries": len(typed),
            "hitsAt8": sum(bool(row["ranking"]["hitAt8"]) for row in typed),
            "hitRateAt8": sum(bool(row["ranking"]["hitAt8"]) for row in typed) / len(typed),
            "falsePositives": sum(bool(row["falsePositive"]) for row in typed),
            "providerOrFormatFailures": sum(not row["providerOrFormatSuccess"] for row in typed),
            "wilson95HitAt8": _wilson(
                sum(bool(row["ranking"]["hitAt8"]) for row in typed), len(typed),
            ),
        }

    by_match_mode = {}
    for match_mode in ("exact", "forward", "reverse", "alias"):
        matched = [row for row in primary if row.get("matchMode") == match_mode]
        by_match_mode[match_mode] = {
            "queries": len(matched),
            "hitsAt8": sum(bool(row["ranking"]["hitAt8"]) for row in matched),
            "hitRateAt8": (
                sum(bool(row["ranking"]["hitAt8"]) for row in matched) / len(matched)
                if matched else 0.0
            ),
            "providerOrFormatFailures": sum(not row["providerOrFormatSuccess"] for row in matched),
            "irrelevantReturnedAt8": sum(row["ranking"]["irrelevantReturnedAt8"] for row in matched),
        }

    successful_negative = [row for row in negative if row.get("providerOrFormatSuccess")]
    successful_false_positives = sum(bool(row["falsePositive"]) for row in successful_negative)
    successful_calls = sum(bool(row["providerOrFormatSuccess"]) for row in rows)
    format_errors = sum(row.get("status") == "format_error" for row in rows)
    provider_errors = len(rows) - successful_calls - format_errors
    filter_rates = {}
    for name in ("region", "category", "price"):
        applicable = [row for row in primary if row[f"{name}FilterApplicable"]]
        successful_applicable = [row for row in applicable if row["providerOrFormatSuccess"]]
        eligible = len(successful_applicable)
        violations = sum(bool(row[f"{name}FilterViolation"]) for row in successful_applicable)
        filter_rates[name] = {
            "eligible": eligible, "violations": violations,
            "failedApplicableQueries": len(applicable) - eligible,
            "rate": violations / eligible if eligible else 0.0,
            "wilson95": _wilson(violations, eligible),
        }
    return {
        "schemaVersion": "miriyum-staging-search-aggregate-v1",
        "effectivenessUnit": "unique-query-repeat-0",
        "effectivenessQueries": total,
        "analyzedCalls": len(rows),
        "providerOrFormatSuccessCalls": successful_calls,
        "providerOrFormatSuccessRate": successful_calls / len(rows) if rows else 0.0,
        "formatErrorCalls": format_errors,
        "providerErrorCalls": provider_errors,
        "failureBreakdown": dict(sorted(failure_breakdown.items())),
        "ranking": ranking,
        "negativeFalsePositive": {
            "queries": len(negative),
            "successfulQueries": len(successful_negative),
            "failedQueries": len(negative) - len(successful_negative),
            "falsePositives": successful_false_positives,
            "rate": successful_false_positives / len(successful_negative) if successful_negative else 0.0,
        },
        "filterViolations": {
            "region": sum(bool(row["regionFilterViolation"]) for row in primary),
            "category": sum(bool(row["categoryFilterViolation"]) for row in primary),
            "price": sum(bool(row["priceFilterViolation"]) for row in primary),
        },
        "filterViolationRates": filter_rates,
        "stability": _stability(rows),
        "latencyMs": {
            "mean": sum(latencies) / len(latencies) if latencies else 0.0,
            "p50": _percentile(latencies, 0.50),
            "p95": _percentile(latencies, 0.95),
            "p99": _percentile(latencies, 0.99),
            "max": max(latencies, default=0.0),
            "overTwoSeconds": sum(value > 2_000 for value in latencies),
            "overTwoSecondsRate": sum(value > 2_000 for value in latencies) / len(latencies) if latencies else 0.0,
        },
        "byQueryType": by_type,
        "byQueryConstructionMatchMode": by_match_mode,
        "wilson95": {
            **wilson,
            "negativeFalsePositive": _wilson(successful_false_positives, len(successful_negative)),
            "expectedTop": _wilson(ranking["expectedTopHits"], ranking["expectedTopQueries"]),
            "pairwiseOrdering": _wilson(pairwise_hits, pairwise_total),
        },
        "notObservable": [
            "llm-semantic-hit", "application-predicate-hit", "all-candidates",
            "exact-forward-reverse-match-mechanism",
            "closed-private-historical-leakage-with-current-corpus",
        ],
    }
