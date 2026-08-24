from __future__ import annotations

from collections import defaultdict
import random
from typing import Any

from .runner import CheckpointStore, EvalConfig, build_request, deterministic_request_id


PILOT_COUNTS = {
    "sensory_without_menu": 40,
    "composite_filter": 20,
    "alias_bidirectional": 15,
    "same_menu_ranking": 10,
    "negative_or_ambiguous_voice": 8,
    "filter_defense": 7,
}


def validate_checkpoint_matrix(
    *, queries: list[dict[str, Any]], records: list[dict[str, Any]], repeats: range,
) -> dict[str, int]:
    repeat_values = tuple(repeats)
    expected = {
        (query["id"], repeat_index)
        for query in queries for repeat_index in repeat_values
    }
    actual = {
        (record.get("queryId"), record.get("repeatIndex"))
        for record in records
    }
    if len(records) != len(expected) or actual != expected:
        missing = len(expected - actual)
        unexpected = len(actual - expected)
        duplicates = len(records) - len(actual)
        raise ValueError(
            "checkpoint matrix mismatch: "
            f"expected={len(expected)}, records={len(records)}, missing={missing}, "
            f"unexpected={unexpected}, duplicatePairs={duplicates}"
        )
    return {
        "queries": len(queries),
        "repeats": len(repeat_values),
        "records": len(records),
    }


def evaluation_config_from_pilot(pilot_calls: list[dict[str, Any]]) -> EvalConfig:
    if len(pilot_calls) != 100 or any(not call.get("providerFormatSuccess") for call in pilot_calls):
        raise ValueError("a frozen successful 100-call pilot is required")
    return EvalConfig(
        estimated_input_tokens=round(sum(int(call["inputTokens"]) for call in pilot_calls) / 100),
        estimated_output_tokens=round(sum(int(call["outputTokens"]) for call in pilot_calls) / 100),
    )


def paid_execution_summary(
    raw_records: list[dict[str, Any]], canonical_records: list[dict[str, Any]],
    *, analyzed_calls: int,
) -> dict[str, Any]:
    newly_paid_canonical = [
        record for record in canonical_records
        if not record.get("canonicalizedFromRequestId")
        and not record.get("migratedFromRequestId")
    ]
    paid_records = raw_records + newly_paid_canonical
    paid_provider_calls = len(paid_records)
    return {
        "analyzedCalls": analyzed_calls,
        "paidProviderCalls": paid_provider_calls,
        "duplicateOverheadCalls": max(0, paid_provider_calls - analyzed_calls),
        "actualPaidCostUsd": sum(float(record.get("costUsd", 0.0)) for record in paid_records),
    }


def stratified_pilot_queries(queries: list[dict[str, Any]], *, seed: int) -> list[dict[str, Any]]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for query in queries:
        grouped[query["type"]].append(query)
    selected = []
    for offset, (query_type, count) in enumerate(PILOT_COUNTS.items()):
        candidates = list(grouped[query_type])
        if query_type == "negative_or_ambiguous_voice":
            for subtype_offset, subtype in enumerate(("ambiguous_food", "true_no_answer")):
                subtype_candidates = [query for query in candidates if query.get("subtype") == subtype]
                random.Random(seed + offset * 10_007 + subtype_offset).shuffle(subtype_candidates)
                if len(subtype_candidates) < count // 2:
                    raise ValueError(f"not enough pilot queries for voice subtype {subtype}")
                selected.extend(subtype_candidates[:count // 2])
            continue
        random.Random(seed + offset * 10_007).shuffle(candidates)
        if len(candidates) < count:
            raise ValueError(f"not enough queries for pilot stratum {query_type}")
        selected.extend(candidates[:count])
    return sorted(selected, key=lambda query: query["id"])


def pilot_gate(
    calls: list[dict[str, Any]], *, projected_call_count: int,
    cost_cap_usd: float, resume_duplicate_calls: int,
) -> dict[str, Any]:
    fatal_reasons = []
    if len(calls) != 100:
        fatal_reasons.append("pilot_count")
    provider_failures = sum(not call["providerFormatSuccess"] for call in calls)
    if provider_failures:
        fatal_reasons.append("provider_or_format_failure")
    if resume_duplicate_calls:
        fatal_reasons.append("checkpoint_resume_duplicate")
    mean_cost = sum(float(call["costUsd"]) for call in calls) / len(calls) if calls else 0.0
    projected_cost = mean_cost * projected_call_count
    if projected_cost > cost_cap_usd:
        fatal_reasons.append("projected_cost_cap")
    no_answer_calls = [
        call for call in calls
        if call.get("querySubtype") == "true_no_answer" and call.get("goldNegative") is True
    ]
    no_answer_false_positive_rate = (
        sum(bool(call.get("falsePositive")) for call in no_answer_calls) / len(no_answer_calls)
        if no_answer_calls else 0.0
    )
    if no_answer_calls and no_answer_false_positive_rate > 0.25:
        fatal_reasons.append("severe_no_answer_false_positive_rate")
    return {
        "schemaVersion": "miriyum-search-pilot-gate-v2",
        "passed": not fatal_reasons,
        "fatalReasons": fatal_reasons,
        "pilotCalls": len(calls),
        "providerOrFormatFailures": provider_failures,
        "resumeDuplicateCalls": resume_duplicate_calls,
        "meanCostUsdPerCall": mean_cost,
        "projectedCompletionCostUsd": projected_cost,
        "costCapUsd": cost_cap_usd,
        "noAnswerFalsePositiveRate": no_answer_false_positive_rate,
        "noAnswerFalsePositiveDenominator": len(no_answer_calls),
        "noAnswerFalsePositiveStopThreshold": 0.25,
    }


def prompt_experiment_gate(
    baseline_calls: list[dict[str, Any]], experiment_calls: list[dict[str, Any]],
    *, minimum_sensory_gain: int,
) -> dict[str, Any]:
    """Gate a paired 100-query prompt experiment on gain and safety.

    Repeated calls are intentionally not accepted here: every query contributes
    exactly one paired observation so the pilot cannot inflate its sample size.
    """
    fatal_reasons: list[str] = []
    baseline_by_id = {call.get("queryId"): call for call in baseline_calls}
    experiment_by_id = {call.get("queryId"): call for call in experiment_calls}
    valid_ids = all(isinstance(query_id, str) for query_id in baseline_by_id | experiment_by_id)
    paired = (
        len(baseline_calls) == len(baseline_by_id) == 100
        and len(experiment_calls) == len(experiment_by_id) == 100
        and baseline_by_id.keys() == experiment_by_id.keys()
        and valid_ids
    )
    if not paired:
        fatal_reasons.append("paired_query_mismatch")

    provider_failures = sum(
        not bool(call.get("providerFormatSuccess")) for call in experiment_calls
    )
    if provider_failures:
        fatal_reasons.append("provider_or_format_failure")

    def cutoff(call: dict[str, Any], name: str) -> dict[str, Any]:
        return call.get("simulatedEvidenceByCutoff", {}).get(name, {})

    common_ids = sorted(baseline_by_id.keys() & experiment_by_id.keys(), key=str)
    sensory_ids = [
        query_id for query_id in common_ids
        if baseline_by_id[query_id].get("queryType") == "sensory_without_menu"
        and experiment_by_id[query_id].get("queryType") == "sensory_without_menu"
    ]
    baseline_sensory_hits = sum(
        bool(cutoff(baseline_by_id[query_id], "@20").get("acceptableHit"))
        for query_id in sensory_ids
    )
    experiment_sensory_hits = sum(
        bool(cutoff(experiment_by_id[query_id], "@20").get("acceptableHit"))
        for query_id in sensory_ids
    )
    sensory_gain = experiment_sensory_hits - baseline_sensory_hits
    if sensory_gain < minimum_sensory_gain:
        fatal_reasons.append("insufficient_sensory_gain")

    filter_ids = [
        query_id for query_id in common_ids
        if baseline_by_id[query_id].get("queryType") == "filter_defense"
        and experiment_by_id[query_id].get("queryType") == "filter_defense"
    ]
    baseline_filter_hits = sum(
        bool(cutoff(baseline_by_id[query_id], "@8").get("acceptableHit"))
        for query_id in filter_ids
    )
    experiment_filter_hits = sum(
        bool(cutoff(experiment_by_id[query_id], "@8").get("acceptableHit"))
        for query_id in filter_ids
    )
    if experiment_filter_hits < baseline_filter_hits:
        fatal_reasons.append("filter_defense_regression")

    gold_negative_ids = [
        query_id for query_id in common_ids
        if baseline_by_id[query_id].get("goldNegative") is True
        and experiment_by_id[query_id].get("goldNegative") is True
    ]
    baseline_negative_fp = sum(
        bool(cutoff(baseline_by_id[query_id], "@8").get("strictFalsePositive"))
        for query_id in gold_negative_ids
    )
    experiment_negative_fp = sum(
        bool(cutoff(experiment_by_id[query_id], "@8").get("strictFalsePositive"))
        for query_id in gold_negative_ids
    )
    if experiment_negative_fp > baseline_negative_fp:
        fatal_reasons.append("gold_negative_false_positive_regression")

    safety_violations = {
        field: sum(bool(call.get(field)) for call in experiment_calls)
        for field in ("closedLeak", "privateOrHistoricalLeak", "filterViolation")
    }
    if any(safety_violations.values()):
        fatal_reasons.append("application_safety_violation")

    return {
        "schemaVersion": "miriyum-search-prompt-experiment-gate-v1",
        "passed": not fatal_reasons,
        "fatalReasons": fatal_reasons,
        "pairedQueries": len(common_ids),
        "providerOrFormatFailures": provider_failures,
        "sensoryQueries": len(sensory_ids),
        "baselineSensoryAcceptableAt20Hits": baseline_sensory_hits,
        "experimentSensoryAcceptableAt20Hits": experiment_sensory_hits,
        "sensoryAcceptableAt20Gain": sensory_gain,
        "minimumSensoryGain": minimum_sensory_gain,
        "filterDefenseQueries": len(filter_ids),
        "baselineFilterDefenseAcceptableAt8Hits": baseline_filter_hits,
        "experimentFilterDefenseAcceptableAt8Hits": experiment_filter_hits,
        "goldNegativeQueries": len(gold_negative_ids),
        "baselineGoldNegativeFalsePositivesAt8": baseline_negative_fp,
        "experimentGoldNegativeFalsePositivesAt8": experiment_negative_fp,
        "safetyViolations": safety_violations,
    }


def model_comparison_summary(
    baseline_calls: list[dict[str, Any]], comparison_calls: list[dict[str, Any]],
) -> dict[str, Any]:
    baseline = {call.get("queryId"): call for call in baseline_calls}
    comparison = {call.get("queryId"): call for call in comparison_calls}
    if (
        len(baseline_calls) != len(baseline)
        or len(comparison_calls) != len(comparison)
        or baseline.keys() != comparison.keys()
    ):
        raise ValueError("model comparison requires exactly paired unique queries")

    def cutoff(call: dict[str, Any], label: str) -> dict[str, Any]:
        return call.get("simulatedEvidenceByCutoff", {}).get(label, {})

    def actual_cutoff(call: dict[str, Any], label: str) -> dict[str, Any]:
        return call.get("finalApplicationByCutoff", {}).get(label, {})

    ids = sorted(baseline, key=str)
    sensory = [query_id for query_id in ids if baseline[query_id].get("queryType") == "sensory_without_menu"]
    negative = [query_id for query_id in ids if baseline[query_id].get("goldNegative") is True]

    def hits(rows: dict[Any, dict[str, Any]], query_ids: list[Any], label: str, field: str) -> int:
        return sum(bool(cutoff(rows[query_id], label).get(field)) for query_id in query_ids)

    base_strict8 = hits(baseline, ids, "@8", "strictHit")
    comp_strict8 = hits(comparison, ids, "@8", "strictHit")
    base_acceptable8 = hits(baseline, ids, "@8", "acceptableHit")
    comp_acceptable8 = hits(comparison, ids, "@8", "acceptableHit")
    base_sensory20 = hits(baseline, sensory, "@20", "acceptableHit")
    comp_sensory20 = hits(comparison, sensory, "@20", "acceptableHit")
    base_negative_fp = hits(baseline, negative, "@8", "strictFalsePositive")
    comp_negative_fp = hits(comparison, negative, "@8", "strictFalsePositive")
    base_actual_hits = sum(
        bool(baseline[query_id].get("actualApplicationPredicate", {}).get("hit"))
        for query_id in ids
    )
    comp_actual_hits = sum(
        bool(comparison[query_id].get("actualApplicationPredicate", {}).get("hit"))
        for query_id in ids
    )
    base_actual_strict8 = sum(bool(actual_cutoff(baseline[query_id], "@8").get("strictHit")) for query_id in ids)
    comp_actual_strict8 = sum(bool(actual_cutoff(comparison[query_id], "@8").get("strictHit")) for query_id in ids)
    base_actual_acceptable8 = sum(bool(actual_cutoff(baseline[query_id], "@8").get("acceptableHit")) for query_id in ids)
    comp_actual_acceptable8 = sum(bool(actual_cutoff(comparison[query_id], "@8").get("acceptableHit")) for query_id in ids)
    return {
        "schemaVersion": "miriyum-search-model-comparison-v2",
        "pairedQueries": len(ids),
        "sensoryQueries": len(sensory),
        "goldNegativeQueries": len(negative),
        "baselineProviderOrFormatFailures": sum(not call.get("providerFormatSuccess") for call in baseline_calls),
        "comparisonProviderOrFormatFailures": sum(not call.get("providerFormatSuccess") for call in comparison_calls),
        "actualApplicationPredicate": {
            "baselineHits": base_actual_hits,
            "comparisonHits": comp_actual_hits,
            "hitGain": comp_actual_hits - base_actual_hits,
        },
        "actualApplicationFinalSearch": {
            "baselineStrictAt8Hits": base_actual_strict8,
            "comparisonStrictAt8Hits": comp_actual_strict8,
            "strictAt8Gain": comp_actual_strict8 - base_actual_strict8,
            "baselineAcceptableAt8Hits": base_actual_acceptable8,
            "comparisonAcceptableAt8Hits": comp_actual_acceptable8,
            "acceptableAt8Gain": comp_actual_acceptable8 - base_actual_acceptable8,
        },
        "simulatedEvidence": {
            "baselineStrictAt8Hits": base_strict8,
            "comparisonStrictAt8Hits": comp_strict8,
            "strictAt8Gain": comp_strict8 - base_strict8,
            "baselineAcceptableAt8Hits": base_acceptable8,
            "comparisonAcceptableAt8Hits": comp_acceptable8,
            "acceptableAt8Gain": comp_acceptable8 - base_acceptable8,
            "baselineSensoryAcceptableAt20Hits": base_sensory20,
            "comparisonSensoryAcceptableAt20Hits": comp_sensory20,
            "sensoryAcceptableAt20Gain": comp_sensory20 - base_sensory20,
            "baselineGoldNegativeFalsePositivesAt8": base_negative_fp,
            "comparisonGoldNegativeFalsePositivesAt8": comp_negative_fp,
            "goldNegativeFalsePositiveAt8Gain": comp_negative_fp - base_negative_fp,
        },
    }


def paired_reanalysis_summary(
    baseline_calls: list[dict[str, Any]], comparison_calls: list[dict[str, Any]],
) -> dict[str, Any]:
    """Compare two predicates on exactly the same provider checkpoint calls."""
    baseline_by_key = {
        (call.get("queryId"), call.get("repeatIndex")): call
        for call in baseline_calls
    }
    comparison_by_key = {
        (call.get("queryId"), call.get("repeatIndex")): call
        for call in comparison_calls
    }
    if (
        len(baseline_by_key) != len(baseline_calls)
        or len(comparison_by_key) != len(comparison_calls)
        or baseline_by_key.keys() != comparison_by_key.keys()
    ):
        raise ValueError("paired call keys do not match")

    def false_positives(calls: dict[tuple[Any, Any], dict[str, Any]], *, true_no_answer: bool) -> int:
        return sum(
            bool(call.get("falsePositive"))
            for call in calls.values()
            if call.get("goldNegative") is True
            and (not true_no_answer or call.get("querySubtype") == "true_no_answer")
        )

    baseline_true_no_answer = false_positives(baseline_by_key, true_no_answer=True)
    comparison_true_no_answer = false_positives(comparison_by_key, true_no_answer=True)
    true_no_answer_gain = comparison_true_no_answer - baseline_true_no_answer
    if true_no_answer_gain > 0:
        raise RuntimeError(
            "true-no-answer false positives increased in paired reanalysis"
        )
    baseline_gold_negative = false_positives(baseline_by_key, true_no_answer=False)
    comparison_gold_negative = false_positives(comparison_by_key, true_no_answer=False)
    return {
        "schemaVersion": "miriyum-search-paired-reanalysis-v1",
        "pairedCalls": len(baseline_by_key),
        "baselinePredicateVariant": "pre-issue-616",
        "comparisonPredicateVariant": "issue-616-most-specific",
        "baselineTrueNoAnswerFalsePositives": baseline_true_no_answer,
        "comparisonTrueNoAnswerFalsePositives": comparison_true_no_answer,
        "trueNoAnswerFalsePositiveGain": true_no_answer_gain,
        "baselineGoldNegativeFalsePositives": baseline_gold_negative,
        "comparisonGoldNegativeFalsePositives": comparison_gold_negative,
        "goldNegativeFalsePositiveGain": comparison_gold_negative - baseline_gold_negative,
    }


def migrate_unchanged_calls(
    *, old_queries: list[dict[str, Any]], new_queries: list[dict[str, Any]],
    old_records: list[dict[str, Any]], new_dataset_sha: str,
    config: EvalConfig, destination: CheckpointStore,
) -> dict[str, int]:
    old_text = {query["id"]: query["text"] for query in old_queries}
    new_text = {query["id"]: query["text"] for query in new_queries}
    migrated = 0
    changed_or_missing = 0
    for record in old_records:
        query_id = record.get("queryId")
        repeat_index = record.get("repeatIndex")
        if (
            not isinstance(query_id, str)
            or not isinstance(repeat_index, int)
            or record.get("requestFingerprint") != config.fingerprint()
            or old_text.get(query_id) != new_text.get(query_id)
        ):
            changed_or_missing += 1
            continue
        migrated_record = dict(record)
        migrated_record["migratedFromRequestId"] = record["requestId"]
        migrated_record["requestId"] = deterministic_request_id(new_dataset_sha, query_id, repeat_index, config)
        destination.append(migrated_record)
        migrated += 1
    return {"migrated": migrated, "changedOrMissing": changed_or_missing}


def canonicalize_equivalent_calls(
    *, queries: list[dict[str, Any]], records: list[dict[str, Any]],
    dataset_sha: str, source_configs: tuple[EvalConfig, ...],
    destination_config: EvalConfig, preferred_request_ids: set[str],
    destination: CheckpointStore,
) -> dict[str, int]:
    """Select one paid response per query/repeat after proving request bodies equivalent."""
    query_by_id = {query["id"]: query for query in queries}
    config_by_fingerprint = {config.fingerprint(): config for config in source_configs}
    grouped: dict[tuple[str, int], list[dict[str, Any]]] = defaultdict(list)
    rejected = 0
    for record in records:
        query = query_by_id.get(record.get("queryId"))
        source_config = config_by_fingerprint.get(record.get("requestFingerprint"))
        repeat_index = record.get("repeatIndex")
        if query is None or source_config is None or not isinstance(repeat_index, int):
            rejected += 1
            continue
        if build_request(query["text"], source_config) != build_request(query["text"], destination_config):
            rejected += 1
            continue
        grouped[(query["id"], repeat_index)].append(record)

    migrated = 0
    already_present = 0
    for (query_id, repeat_index), candidates in sorted(grouped.items()):
        selected = min(
            candidates,
            key=lambda record: (record.get("requestId") not in preferred_request_ids, record.get("requestId", "")),
        )
        request_id = deterministic_request_id(dataset_sha, query_id, repeat_index, destination_config)
        if destination.get(request_id) is not None:
            already_present += 1
            continue
        canonical = dict(selected)
        canonical["canonicalizedFromRequestId"] = selected["requestId"]
        canonical["requestId"] = request_id
        canonical["requestFingerprint"] = destination_config.fingerprint()
        destination.append(canonical)
        migrated += 1
    return {
        "sourceCalls": len(records),
        "canonicalCalls": len(grouped),
        "duplicateEquivalentCalls": sum(len(values) - 1 for values in grouped.values()),
        "rejectedNonEquivalentCalls": rejected,
        "migrated": migrated,
        "alreadyPresent": already_present,
    }
