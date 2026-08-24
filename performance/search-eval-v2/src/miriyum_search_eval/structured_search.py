from __future__ import annotations

from dataclasses import dataclass
from collections import defaultdict
from enum import Enum
import re
from statistics import mean
from typing import Any, Iterable

from .matching import (
    _eligible,
    compact,
    deterministic_remaining_keyword,
    merge_application_candidates,
    normalize,
    retrieve_original_candidates,
)
from .metrics import ranking_metrics, stability_metrics, wilson_interval


class EvidenceSource(str, Enum):
    DETERMINISTIC = "DETERMINISTIC"
    LLM = "LLM"


@dataclass(frozen=True)
class StructuredFoodEvidence:
    raw_food_spans: tuple[str, ...]
    menu_families: tuple[str, ...]
    family_ids: tuple[str, ...]
    ingredients: tuple[str, ...]
    tastes: tuple[str, ...]
    broths: tuple[str, ...]
    methods: tuple[str, ...]
    forms: tuple[str, ...]
    sources: dict[str, EvidenceSource]


@dataclass(frozen=True)
class StructuredCandidateResult:
    menu_ids: tuple[str, ...]
    store_ids: tuple[str, ...]
    evidence_counts: dict[str, int]
    raw_exact_menu_ids: tuple[str, ...]
    family_match_menu_ids: tuple[str, ...]


@dataclass(frozen=True)
class StructuredPreparedCatalog:
    family_by_id: dict[str, dict[str, Any]]
    store_by_id: dict[str, dict[str, Any]]
    menus_by_family: dict[str, tuple[dict[str, Any], ...]]
    menus_by_name: dict[str, tuple[dict[str, Any], ...]]


_DIMENSION_FIELDS = {
    "ingredients": "ingredient",
    "tastes": "taste",
    "broths": "broth",
    "methods": "method",
}
STRUCTURED_CUTOFFS: dict[str, int | None] = {
    "@1": 1, "@3": 3, "@5": 5, "@8": 8,
    "@20": 20, "@50": 50, "all": None,
}


def _deduplicate(values: Iterable[str]) -> tuple[str, ...]:
    return tuple(dict.fromkeys(normalize(value) for value in values if normalize(value)))


def _family_terms(family: dict[str, Any]) -> tuple[str, ...]:
    return _deduplicate((
        family.get("canonical", ""), family.get("baseName", ""),
        *family.get("aliases", []), *family.get("variants", []),
    ))


def _family_term_score(text: str, family: dict[str, Any]) -> tuple[int, int]:
    value = compact(text)
    canonical = family.get("canonical", "")
    scored_terms = (
        (canonical, 3),
        *((term, 3) for term in family.get("variants", [])),
        *((term, 2) for term in family.get("aliases", [])),
        (family.get("baseName", ""), 1),
    )
    scores = [
        (len(compact(term)), tier)
        for term, tier in scored_terms
        if compact(term) and compact(term) in value
    ]
    return max(scores, default=(0, 0))


def _matching_families(text: str, families: list[dict[str, Any]]) -> list[dict[str, Any]]:
    scored = [(family, _family_term_score(text, family)) for family in families]
    best_length = max((score[0] for _, score in scored), default=0)
    return [family for family, score in scored if score[0] == best_length and best_length]


def _matching_attribute_values(
    text: str, families: list[dict[str, Any]], attribute: str,
) -> tuple[str, ...]:
    value = compact(text)
    candidates = _deduplicate(
        str(family.get("attributes", {}).get(attribute, ""))
        for family in families
    )
    return tuple(candidate for candidate in candidates if compact(candidate) in value)


def _refine_families_by_attributes(
    families: list[dict[str, Any]], dimensions: dict[str, tuple[str, ...]],
    *, text: str,
) -> list[dict[str, Any]]:
    if len(families) < 2:
        return families
    scored = [
        (family, sum(
            compact(str(family.get("attributes", {}).get(attribute, "")))
            in {compact(value) for value in dimensions[field]}
            for field, attribute in _DIMENSION_FIELDS.items()
            if dimensions[field]
        ))
        for family in families
    ]
    best = max(score for _, score in scored)
    refined = [family for family, score in scored if score == best] if best else families
    if len(refined) < 2:
        return refined
    best_term = max(_family_term_score(text, family) for family in refined)
    return [
        family for family in refined
        if _family_term_score(text, family) == best_term
    ]


def _matching_forms(text: str) -> tuple[str, ...]:
    value = compact(text)
    return tuple(form for form in ("면", "밥") if form in value)


def _raw_food_spans(
    text: str, matched_families: list[dict[str, Any]],
    attribute_values: Iterable[str],
) -> tuple[str, ...]:
    normalized = normalize(text)
    terms = sorted(
        {term for family in matched_families for term in _family_terms(family)},
        key=lambda value: (-len(value), value),
    )
    modifiers = sorted(set(attribute_values), key=lambda value: (-len(value), value))
    modifier_pattern = "|".join(re.escape(value) for value in modifiers)
    spans = []
    for term in terms:
        prefix = f"(?:(?:{modifier_pattern})\\s+)*" if modifier_pattern else ""
        match = re.search(rf"(?<!\S){prefix}{re.escape(term)}(?!\S)", normalized)
        if match:
            spans.append(match.group(0))
    if not spans:
        return ()
    longest = max(spans, key=lambda value: (len(value), value))
    return (longest,)


def extract_structured_food_evidence(
    query_text: str, *, families: list[dict[str, Any]],
    interpretation: str | None, llm_concepts: list[str],
) -> StructuredFoodEvidence:
    matched_families = _matching_families(query_text, families)
    dimensions = {
        field: _matching_attribute_values(query_text, families, attribute)
        for field, attribute in _DIMENSION_FIELDS.items()
    }
    matched_families = _refine_families_by_attributes(
        matched_families, dimensions, text=query_text,
    )
    forms = _matching_forms(query_text)
    sources: dict[str, EvidenceSource] = {}
    menu_families = _deduplicate(
        str(family.get("baseName") or family.get("canonical", ""))
        for family in matched_families
    )
    family_ids = _deduplicate(str(family["id"]) for family in matched_families)
    if menu_families:
        sources["menuFamilies"] = EvidenceSource.DETERMINISTIC
    for field, values in dimensions.items():
        if values:
            sources[field] = EvidenceSource.DETERMINISTIC
    if forms:
        sources["forms"] = EvidenceSource.DETERMINISTIC

    if interpretation == "MATCHABLE":
        llm_text = " ".join(llm_concepts)
        llm_families = _matching_families(llm_text, families)
        if not menu_families and llm_families:
            menu_families = _deduplicate(
                str(family.get("baseName") or family.get("canonical", ""))
                for family in llm_families
            )
            family_ids = _deduplicate(str(family["id"]) for family in llm_families)
            sources["menuFamilies"] = EvidenceSource.LLM
        for field, attribute in _DIMENSION_FIELDS.items():
            if dimensions[field]:
                continue
            values = _matching_attribute_values(llm_text, families, attribute)
            if values:
                dimensions[field] = values
                sources[field] = EvidenceSource.LLM
        if not forms:
            forms = _matching_forms(llm_text)
            if forms:
                sources["forms"] = EvidenceSource.LLM

    raw_spans = _raw_food_spans(
        query_text,
        matched_families,
        (*dimensions["ingredients"], *dimensions["tastes"],
         *dimensions["broths"], *dimensions["methods"]),
    )
    if raw_spans:
        sources["rawFoodSpans"] = EvidenceSource.DETERMINISTIC
    return StructuredFoodEvidence(
        raw_food_spans=raw_spans,
        menu_families=menu_families,
        family_ids=family_ids,
        ingredients=dimensions["ingredients"],
        tastes=dimensions["tastes"],
        broths=dimensions["broths"],
        methods=dimensions["methods"],
        forms=forms,
        sources=sources,
    )


def _attribute_evidence_count(
    evidence: StructuredFoodEvidence, family: dict[str, Any],
) -> int:
    attributes = family.get("attributes", {})
    return sum(
        bool(values)
        and compact(str(attributes.get(attribute, ""))) in {
            compact(value) for value in values
        }
        for field, attribute in _DIMENSION_FIELDS.items()
        for values in (getattr(evidence, field),)
    )


def _candidate_sort_key(
    *, menu: dict[str, Any], store: dict[str, Any], sort: str,
    ranking: str, raw_exact: bool, family_match: bool, evidence_count: int,
) -> tuple[Any, ...]:
    if sort == "DISTANCE":
        business = (store["distanceMeters"], -store["recommendationScore"])
    elif sort == "RATING":
        business = (
            -store["rating"], -store["recommendationScore"],
            store["distanceMeters"],
        )
    else:
        business = (-store["recommendationScore"], store["distanceMeters"])
    if ranking == "structured" and sort == "RECOMMENDED":
        return (
            -int(raw_exact), -int(family_match), -evidence_count,
            *business, menu["id"],
        )
    return (*business, menu["id"])


def prepare_structured_catalog(
    *, families: list[dict[str, Any]], stores: list[dict[str, Any]],
    menus: list[dict[str, Any]],
) -> StructuredPreparedCatalog:
    menus_by_family: dict[str, list[dict[str, Any]]] = defaultdict(list)
    menus_by_name: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for menu in menus:
        menus_by_family[menu["familyId"]].append(menu)
        menus_by_name[compact(menu.get("name", ""))].append(menu)
    return StructuredPreparedCatalog(
        family_by_id={family["id"]: family for family in families},
        store_by_id={store["id"]: store for store in stores},
        menus_by_family={
            key: tuple(values) for key, values in menus_by_family.items()
        },
        menus_by_name={key: tuple(values) for key, values in menus_by_name.items()},
    )


def retrieve_structured_candidates(
    *, evidence: StructuredFoodEvidence, families: list[dict[str, Any]],
    stores: list[dict[str, Any]], menus: list[dict[str, Any]],
    filters: dict[str, Any], sort: str, ranking: str,
    prepared_catalog: StructuredPreparedCatalog | None = None,
) -> StructuredCandidateResult:
    if ranking not in {"current", "structured"}:
        raise ValueError(f"unknown structured ranking: {ranking}")
    prepared_catalog = prepared_catalog or prepare_structured_catalog(
        families=families, stores=stores, menus=menus,
    )
    family_by_id = prepared_catalog.family_by_id
    store_by_id = prepared_catalog.store_by_id
    evidence_family_names = {compact(value) for value in evidence.menu_families}
    evidence_family_ids = set(evidence.family_ids)
    raw_spans = {compact(value) for value in evidence.raw_food_spans}
    if evidence_family_ids:
        candidate_family_ids = evidence_family_ids & set(family_by_id)
    elif evidence_family_names:
        candidate_family_ids = {
            family_id for family_id, family in family_by_id.items()
            if compact(str(family.get("baseName", ""))) in evidence_family_names
        }
    else:
        candidate_family_ids = {
            family_id for family_id, family in family_by_id.items()
            if _attribute_evidence_count(evidence, family) >= 2
        }
    candidate_menus = {
        menu["id"]: menu
        for family_id in candidate_family_ids
        for menu in prepared_catalog.menus_by_family.get(family_id, ())
    }
    for raw_span in raw_spans:
        for menu in prepared_catalog.menus_by_name.get(raw_span, ()):
            candidate_menus[menu["id"]] = menu
    rows = []
    evidence_counts: dict[str, int] = {}
    raw_exact_ids = []
    family_match_ids = []
    for menu in candidate_menus.values():
        store = store_by_id[menu["storeId"]]
        if not _eligible(store, menu, filters):
            continue
        family = family_by_id.get(menu["familyId"], {})
        raw_exact = compact(menu.get("name", "")) in raw_spans
        family_match = (
            menu.get("familyId") in evidence_family_ids
            or (
                not evidence_family_ids
                and compact(str(family.get("baseName", ""))) in evidence_family_names
            )
        )
        evidence_count = _attribute_evidence_count(evidence, family)
        attribute_candidate = not evidence_family_names and evidence_count >= 2
        if not (raw_exact or family_match or attribute_candidate):
            continue
        evidence_counts[menu["id"]] = evidence_count
        if raw_exact:
            raw_exact_ids.append(menu["id"])
        if family_match:
            family_match_ids.append(menu["id"])
        rows.append((menu, store, raw_exact, family_match, evidence_count))
    rows.sort(key=lambda row: _candidate_sort_key(
        menu=row[0], store=row[1], sort=sort, ranking=ranking,
        raw_exact=row[2], family_match=row[3], evidence_count=row[4],
    ))
    return StructuredCandidateResult(
        menu_ids=tuple(row[0]["id"] for row in rows),
        store_ids=tuple(dict.fromkeys(row[1]["id"] for row in rows)),
        evidence_counts=evidence_counts,
        raw_exact_menu_ids=tuple(raw_exact_ids),
        family_match_menu_ids=tuple(family_match_ids),
    )


def _gold_hit(ranked: tuple[str, ...], gold: dict[str, Any]) -> bool:
    return not ranked if gold["negative"] else bool(set(ranked) & set(gold["storeIds"]))


def _gold_recall(ranked: tuple[str, ...], gold: dict[str, Any]) -> float:
    relevant = set(gold["storeIds"])
    return len(set(ranked) & relevant) / len(relevant) if relevant else float(not ranked)


def _cutoff_result(
    ranked: tuple[str, ...], strict_gold: dict[str, Any],
    acceptable_gold: dict[str, Any],
) -> dict[str, Any]:
    strict_hit = _gold_hit(ranked, strict_gold)
    acceptable_hit = _gold_hit(ranked, acceptable_gold)
    return {
        "rankedStoreIds": list(ranked),
        "strictHit": strict_hit,
        "acceptableHit": acceptable_hit,
        "strictRecall": _gold_recall(ranked, strict_gold),
        "acceptableRecall": _gold_recall(ranked, acceptable_gold),
        "strictFalsePositive": (
            bool(ranked) if strict_gold["negative"] else bool(ranked) and not strict_hit
        ),
        "acceptableFalsePositive": (
            bool(ranked) if acceptable_gold["negative"]
            else bool(ranked) and not acceptable_hit
        ),
        "ranking": ranking_metrics(list(ranked), strict_gold["storeIds"]),
    }


def _variant_cutoffs(
    full_ranking: tuple[str, ...], strict_gold: dict[str, Any],
    acceptable_gold: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    return {
        label: _cutoff_result(
            full_ranking if size is None else full_ranking[:size],
            strict_gold,
            acceptable_gold,
        )
        for label, size in STRUCTURED_CUTOFFS.items()
    }


def _baseline_full_ranking(baseline_call: dict[str, Any]) -> tuple[str, ...]:
    cutoffs = baseline_call.get("finalApplicationByCutoff", {})
    for label in ("all", "@50", "@20", "@8"):
        ranked = cutoffs.get(label, {}).get("rankedStoreIds")
        if ranked is not None:
            return tuple(ranked)
    return tuple(baseline_call.get("rankedStoreIds", []))


def _candidate_safety(
    *, menu_ids: Iterable[str], store_ids: Iterable[str],
    stores: list[dict[str, Any]], menus: list[dict[str, Any]],
    filters: dict[str, Any], forbidden_menu_ids: set[str],
    forbidden_store_ids: set[str],
) -> dict[str, bool]:
    store_by_id = {store["id"]: store for store in stores}
    menu_by_id = {menu["id"]: menu for menu in menus}
    selected_menus = [menu_by_id[value] for value in menu_ids if value in menu_by_id]
    selected_stores = [store_by_id[value] for value in store_ids if value in store_by_id]
    store_state_leak = any(
        store["id"] in forbidden_store_ids
        or store.get("verificationStatus") != "APPROVED"
        or store.get("operationStatus") == "CLOSED"
        for store in selected_stores
    )
    menu_state_leak = any(
        menu["id"] in forbidden_menu_ids
        or menu.get("retired", False)
        or menu.get("visibility") != "VISIBLE"
        for menu in selected_menus
    )
    filter_violation = any(
        (filters.get("region") and store_by_id[menu["storeId"]]["region"] != filters["region"])
        or (
            filters.get("ambience")
            and store_by_id[menu["storeId"]].get("ambience") != filters["ambience"]
        )
        or (filters.get("maxPrice") is not None and menu["price"] > filters["maxPrice"])
        or (filters.get("category") and menu["category"] != filters["category"])
        for menu in selected_menus
    )
    return {
        "closedOrForbiddenStoreLeak": store_state_leak,
        "privateOrHistoricalMenuLeak": menu_state_leak,
        "filterViolation": filter_violation,
    }


def _evidence_json(evidence: StructuredFoodEvidence) -> dict[str, Any]:
    return {
        "rawFoodSpans": list(evidence.raw_food_spans),
        "menuFamilies": list(evidence.menu_families),
        "familyIds": list(evidence.family_ids),
        "ingredients": list(evidence.ingredients),
        "tastes": list(evidence.tastes),
        "broths": list(evidence.broths),
        "methods": list(evidence.methods),
        "forms": list(evidence.forms),
        "sources": {key: value.value for key, value in evidence.sources.items()},
    }


def evaluate_structured_variants(
    *, dataset: dict[str, Any], query: dict[str, Any],
    record: dict[str, Any], baseline_call: dict[str, Any],
    prepared_catalog: StructuredPreparedCatalog | None = None,
    retrieval_cache: dict[tuple[Any, ...], Any] | None = None,
) -> dict[str, Any]:
    concepts = record.get("concepts", []) if record.get("status") == "success" else []
    evidence_key = (
        "structured-evidence", query["id"], record.get("interpretation"),
        tuple(concepts),
    )
    evidence = retrieval_cache.get(evidence_key) if retrieval_cache is not None else None
    if evidence is None:
        evidence = extract_structured_food_evidence(
            query["text"], families=dataset["families"],
            interpretation=record.get("interpretation"), llm_concepts=concepts,
        )
        if retrieval_cache is not None:
            retrieval_cache[evidence_key] = evidence
    candidate_key = ("structured-candidates", evidence_key)
    candidate_pair = (
        retrieval_cache.get(candidate_key) if retrieval_cache is not None else None
    )
    if candidate_pair is None:
        current = retrieve_structured_candidates(
            evidence=evidence, families=dataset["families"],
            stores=dataset["stores"], menus=dataset["menus"],
            filters=query["filters"], sort=query["sort"], ranking="current",
            prepared_catalog=prepared_catalog,
        )
        structured = retrieve_structured_candidates(
            evidence=evidence, families=dataset["families"],
            stores=dataset["stores"], menus=dataset["menus"],
            filters=query["filters"], sort=query["sort"], ranking="structured",
            prepared_catalog=prepared_catalog,
        )
        candidate_pair = (current, structured)
        if retrieval_cache is not None:
            retrieval_cache[candidate_key] = candidate_pair
    else:
        current, structured = candidate_pair
    original_key = ("structured-original", query["id"])
    original = retrieval_cache.get(original_key) if retrieval_cache is not None else None
    if original is None:
        original = retrieve_original_candidates(
            remaining_keyword=deterministic_remaining_keyword(query),
            stores=dataset["stores"], menus=dataset["menus"],
            filters=query["filters"], sort=query["sort"], guarded_reverse=True,
        )
        if retrieval_cache is not None:
            retrieval_cache[original_key] = original
    baseline_ranking = _baseline_full_ranking(baseline_call)
    current_ranking = merge_application_candidates(
        original_store_ids=original.store_ids,
        supplement_store_ids=current.store_ids,
        page_size=None,
    )
    if query["sort"] == "RECOMMENDED":
        structured_ranking = merge_application_candidates(
            original_store_ids=structured.store_ids,
            supplement_store_ids=original.store_ids,
            page_size=None,
        )
    else:
        structured_ranking = current_ranking
    acceptable_gold = query.get("acceptableGold", query["gold"])
    variants = {
        "A": {
            "label": "current-candidates-current-ranking",
            "cutoffs": _variant_cutoffs(
                baseline_ranking, query["gold"], acceptable_gold,
            ),
        },
        "B": {
            "label": "structured-candidates-current-ranking",
            "cutoffs": _variant_cutoffs(
                current_ranking, query["gold"], acceptable_gold,
            ),
        },
        "C": {
            "label": "structured-candidates-structured-ranking",
            "cutoffs": _variant_cutoffs(
                structured_ranking, query["gold"], acceptable_gold,
            ),
        },
    }
    forbidden_menus = set(query["gold"].get("forbiddenMenuIds", []))
    forbidden_stores = set(query["gold"].get("forbiddenStoreIds", []))
    current_safety = _candidate_safety(
        menu_ids=(*original.menu_ids, *current.menu_ids),
        store_ids=current_ranking,
        stores=dataset["stores"], menus=dataset["menus"], filters=query["filters"],
        forbidden_menu_ids=forbidden_menus, forbidden_store_ids=forbidden_stores,
    )
    structured_safety = _candidate_safety(
        menu_ids=(*original.menu_ids, *structured.menu_ids),
        store_ids=structured_ranking,
        stores=dataset["stores"], menus=dataset["menus"], filters=query["filters"],
        forbidden_menu_ids=forbidden_menus, forbidden_store_ids=forbidden_stores,
    )
    variants["A"]["safety"] = {
        "closedOrForbiddenStoreLeak": bool(baseline_call.get("closedLeak")),
        "privateOrHistoricalMenuLeak": bool(baseline_call.get("privateOrHistoricalLeak")),
        "filterViolation": bool(baseline_call.get("filterViolation")),
    }
    variants["B"]["safety"] = current_safety
    variants["C"]["safety"] = structured_safety
    return {
        "schemaVersion": "miriyum-structured-search-call-v1",
        "queryId": query["id"],
        "repeatIndex": record["repeatIndex"],
        "queryType": query["type"],
        "querySubtype": query.get("subtype"),
        "goldNegative": query["gold"]["negative"],
        "evidence": _evidence_json(evidence),
        "structuredCandidateMenuIds": list(current.menu_ids),
        "structuredEvidenceCounts": current.evidence_counts,
        "variants": variants,
    }


def _majority(query_calls: list[dict[str, Any]], variant: str, label: str, field: str) -> bool:
    threshold = len(query_calls) // 2 + 1
    return sum(
        bool(call["variants"][variant]["cutoffs"][label][field])
        for call in query_calls
    ) >= threshold


def _binary_summary(successes: int, total: int) -> dict[str, Any]:
    low, high = wilson_interval(successes, total) if total else (0.0, 0.0)
    return {
        "successes": successes,
        "total": total,
        "rate": successes / total if total else 0.0,
        "wilson95": {"low": low, "high": high},
    }


def aggregate_structured_comparison(calls: list[dict[str, Any]]) -> dict[str, Any]:
    by_query: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for call in calls:
        by_query[call["queryId"]].append(call)
    variants: dict[str, Any] = {}
    majority: dict[tuple[str, str, str, str], bool] = {}
    for variant in ("A", "B", "C"):
        cutoff_rows = {}
        for label in STRUCTURED_CUTOFFS:
            row = {}
            for gold in ("strict", "acceptable"):
                field = f"{gold}Hit"
                successes = 0
                for query_id, query_calls in by_query.items():
                    hit = _majority(query_calls, variant, label, field)
                    majority[(query_id, variant, label, gold)] = hit
                    successes += hit
                call_rows = [
                    call["variants"][variant]["cutoffs"][label]
                    for call in calls
                ]
                row[gold] = {
                    "hit": _binary_summary(successes, len(by_query)),
                    "meanRecall": mean(value[f"{gold}Recall"] for value in call_rows),
                }
            ranking_rows = [
                call["variants"][variant]["cutoffs"][label]["ranking"]
                for call in calls
            ]
            row["ranking"] = {
                key: mean(value[key] for value in ranking_rows)
                for key in ranking_rows[0]
            } if ranking_rows else {}
            cutoff_rows[label] = row
        stability = [
            stability_metrics([
                call["variants"][variant]["cutoffs"]["@8"]["rankedStoreIds"]
                for call in sorted(query_calls, key=lambda value: value["repeatIndex"])
            ])
            for query_calls in by_query.values()
        ]
        variants[variant] = {
            "cutoffs": cutoff_rows,
            "stability": {
                key: mean(value[key] for value in stability)
                for key in stability[0]
            } if stability else {},
        }
    paired_deltas = {}
    for label, left, right in (("AtoB", "A", "B"), ("BtoC", "B", "C")):
        paired_deltas[label] = {
            f"{gold}{cutoff}": sum(
                majority[(query_id, right, cutoff, gold)]
                - majority[(query_id, left, cutoff, gold)]
                for query_id in by_query
            )
            for cutoff in STRUCTURED_CUTOFFS
            for gold in ("strict", "acceptable")
        }
    def grouped_breakdown(group_field: str) -> dict[str, Any]:
        query_groups: dict[str, list[str]] = defaultdict(list)
        for query_id, query_calls in by_query.items():
            value = query_calls[0].get(group_field)
            if value:
                query_groups[str(value)].append(query_id)
        return {
            group: {
                "uniqueQueries": len(query_ids),
                "variants": {
                    variant: {
                        f"{gold}{cutoff}": _binary_summary(
                            sum(
                                majority[(query_id, variant, cutoff, gold)]
                                for query_id in query_ids
                            ),
                            len(query_ids),
                        )
                        for cutoff in STRUCTURED_CUTOFFS
                        for gold in ("strict", "acceptable")
                    }
                    for variant in ("A", "B", "C")
                },
            }
            for group, query_ids in sorted(query_groups.items())
        }
    by_query_type = grouped_breakdown("queryType")
    by_query_subtype = grouped_breakdown("querySubtype")
    safety: dict[str, Any] = {}
    for variant in ("A", "B", "C"):
        true_no_answer = 0
        all_negative = 0
        for query_calls in by_query.values():
            first = query_calls[0]
            if not first["goldNegative"]:
                continue
            false_positive = _majority(
                query_calls, variant, "@8", "strictFalsePositive",
            )
            all_negative += false_positive
            if first.get("querySubtype") == "true_no_answer":
                true_no_answer += false_positive
        safety[variant] = {
            "trueNoAnswerFalsePositives": true_no_answer,
            "allNegativeFalsePositives": all_negative,
            "closedOrForbiddenStoreLeaks": sum(
                bool(call["variants"][variant]["safety"]["closedOrForbiddenStoreLeak"])
                for call in calls
            ),
            "privateOrHistoricalMenuLeaks": sum(
                bool(call["variants"][variant]["safety"]["privateOrHistoricalMenuLeak"])
                for call in calls
            ),
            "filterViolations": sum(
                bool(call["variants"][variant]["safety"]["filterViolation"])
                for call in calls
            ),
            "falsePositivesByCutoff": {
                cutoff: {
                    gold: sum(
                        _majority(
                            query_calls, variant, cutoff, f"{gold}FalsePositive",
                        )
                        for query_calls in by_query.values()
                    )
                    for gold in ("strict", "acceptable")
                }
                for cutoff in STRUCTURED_CUTOFFS
            },
        }
    fatal_reasons = []
    for variant in ("B", "C"):
        if (
            safety[variant]["trueNoAnswerFalsePositives"]
            > safety["A"]["trueNoAnswerFalsePositives"]
        ):
            fatal_reasons.append(
                f"{variant}_true_no_answer_false_positive_regression"
            )
        if (
            safety[variant]["allNegativeFalsePositives"]
            > safety["A"]["allNegativeFalsePositives"]
        ):
            fatal_reasons.append(f"{variant}_all_negative_false_positive_regression")
        for key, reason in (
            ("closedOrForbiddenStoreLeaks", "store_state_leak"),
            ("privateOrHistoricalMenuLeaks", "menu_state_leak"),
            ("filterViolations", "filter_violation"),
        ):
            if safety[variant][key]:
                fatal_reasons.append(f"{variant}_{reason}")
        alias = by_query_type.get("alias_bidirectional")
        if alias:
            for gold in ("strict", "acceptable"):
                key = f"{gold}@8"
                if (
                    alias["variants"][variant][key]["successes"]
                    < alias["variants"]["A"][key]["successes"]
                ):
                    fatal_reasons.append(
                        f"{variant}_alias_bidirectional_{gold}_at8_regression"
                    )
    return {
        "schemaVersion": "miriyum-structured-search-comparison-v1",
        "statisticalUnit": {
            "uniqueQueries": len(by_query),
            "calls": len(calls),
            "repetitionsAreIndependentSamples": False,
        },
        "variants": variants,
        "pairedDeltas": paired_deltas,
        "byQueryType": by_query_type,
        "byQuerySubtype": by_query_subtype,
        "safety": safety,
        "gate": {
            "passed": not fatal_reasons,
            "fatalReasons": fatal_reasons,
            "actualApplication": False,
            "productionActivationApproved": False,
        },
    }
