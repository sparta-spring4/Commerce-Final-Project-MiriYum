from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
import re
from statistics import mean
from typing import Any, Iterable

from .matching import _eligible, compact, merge_application_candidates, normalize
from .metrics import stability_metrics, wilson_interval
from .structured_search import STRUCTURED_CUTOFFS, _candidate_safety, _variant_cutoffs


HYBRID_VARIANTS = ("D", "E", "F", "G")
_CORE_EVIDENCE_FIELDS = {
    "ingredients": "ingredient",
    "tastes": "taste",
    "broths": "broth",
    "methods": "method",
}
_LEXICAL_STOPWORDS = frozenset({
    "가게", "곳", "메뉴", "음식", "요리", "식사", "추천", "추천해줘",
    "찾아줘", "찾아", "에서", "으로", "만든", "같은", "있는", "나는",
    "맛에", "향이", "나고", "국물", "가격", "이하", "이상",
    "면", "탕", "국", "밥", "세트", "정식", "음료",
})


@dataclass(frozen=True)
class HybridPreparedCatalog:
    families_by_id: dict[str, dict[str, Any]]
    stores_by_id: dict[str, dict[str, Any]]
    menus_by_id: dict[str, dict[str, Any]]
    menus_by_store: dict[str, tuple[dict[str, Any], ...]]
    searchable_tokens_by_menu: dict[str, frozenset[str]]


@dataclass(frozen=True)
class LexicalCandidateResult:
    menu_ids: tuple[str, ...]
    store_ids: tuple[str, ...]
    menu_match_counts: dict[str, int]


def _tokens(value: str) -> tuple[str, ...]:
    return tuple(
        normalize(token) for token in re.findall(r"[0-9A-Za-z가-힣]+", value)
        if len(normalize(token)) >= 2
    )


def informative_query_tokens(
    query_text: str, *, filters: dict[str, Any],
) -> tuple[str, ...]:
    value = query_text
    removable = [
        filters.get("region"), filters.get("ambience"), filters.get("category"),
    ]
    if filters.get("maxPrice") is not None:
        removable.append(str(filters["maxPrice"]))
    for item in sorted((str(item) for item in removable if item), key=len, reverse=True):
        value = re.sub(re.escape(item), " ", value, flags=re.IGNORECASE)
    return tuple(dict.fromkeys(
        token for token in _tokens(value)
        if token not in _LEXICAL_STOPWORDS and not token.isdigit()
    ))


def prepare_hybrid_catalog(
    *, families: list[dict[str, Any]], stores: list[dict[str, Any]],
    menus: list[dict[str, Any]],
) -> HybridPreparedCatalog:
    menus_by_store: dict[str, list[dict[str, Any]]] = defaultdict(list)
    searchable_tokens = {}
    for menu in menus:
        menus_by_store[menu["storeId"]].append(menu)
        searchable_tokens[menu["id"]] = frozenset(_tokens(" ".join((
            menu.get("name", ""), menu.get("description", ""),
            *menu.get("tags", []),
        ))))
    return HybridPreparedCatalog(
        families_by_id={family["id"]: family for family in families},
        stores_by_id={store["id"]: store for store in stores},
        menus_by_id={menu["id"]: menu for menu in menus},
        menus_by_store={key: tuple(values) for key, values in menus_by_store.items()},
        searchable_tokens_by_menu=searchable_tokens,
    )


def _business_sort_key(menu: dict[str, Any], store: dict[str, Any], sort: str) -> tuple[Any, ...]:
    if sort == "DISTANCE":
        return (store["distanceMeters"], -store["recommendationScore"], menu["id"])
    if sort == "RATING":
        return (-store["rating"], -store["recommendationScore"], store["distanceMeters"], menu["id"])
    return (-store["recommendationScore"], store["distanceMeters"], menu["id"])


def retrieve_lexical_candidates(
    *, query_text: str, filters: dict[str, Any], sort: str,
    catalog: HybridPreparedCatalog,
) -> LexicalCandidateResult:
    query_tokens = set(informative_query_tokens(query_text, filters=filters))
    if len(query_tokens) < 2:
        return LexicalCandidateResult((), (), {})
    rows = []
    counts = {}
    for menu in catalog.menus_by_id.values():
        store = catalog.stores_by_id[menu["storeId"]]
        if not _eligible(store, menu, filters):
            continue
        match_count = len(query_tokens & catalog.searchable_tokens_by_menu[menu["id"]])
        if match_count < 2:
            continue
        counts[menu["id"]] = match_count
        rows.append((menu, store, match_count))
    rows.sort(key=lambda row: (-row[2], *_business_sort_key(row[0], row[1], sort)))
    return LexicalCandidateResult(
        menu_ids=tuple(row[0]["id"] for row in rows),
        store_ids=tuple(dict.fromkeys(row[1]["id"] for row in rows)),
        menu_match_counts=counts,
    )


def _core_evidence_count(evidence: dict[str, Any]) -> int:
    return sum(bool(evidence.get(field)) for field in _CORE_EVIDENCE_FIELDS)


def _family_evidence_count(evidence: dict[str, Any], family: dict[str, Any]) -> int:
    attributes = family.get("attributes", {})
    return sum(
        compact(str(attributes.get(attribute, "")))
        in {compact(str(value)) for value in evidence.get(field, [])}
        for field, attribute in _CORE_EVIDENCE_FIELDS.items()
        if evidence.get(field)
    )


def _eligible_embedding_candidates(
    *, embedding_menu_scores: Iterable[tuple[str, float]],
    filters: dict[str, Any], catalog: HybridPreparedCatalog,
) -> tuple[tuple[str, ...], tuple[str, ...], dict[str, float]]:
    menus, stores, scores = [], [], {}
    for menu_id, score in embedding_menu_scores:
        menu = catalog.menus_by_id.get(menu_id)
        if menu is None:
            continue
        store = catalog.stores_by_id[menu["storeId"]]
        if not _eligible(store, menu, filters):
            continue
        menus.append(menu_id)
        scores[menu_id] = float(score)
        if store["id"] not in stores:
            stores.append(store["id"])
    return tuple(menus), tuple(stores), scores


def _hybrid_ranking(
    *, candidate_store_ids: tuple[str, ...], baseline_store_ids: tuple[str, ...],
    evidence: dict[str, Any], lexical: LexicalCandidateResult,
    embedding_scores: dict[str, float], filters: dict[str, Any],
    catalog: HybridPreparedCatalog,
) -> tuple[str, ...]:
    baseline_rank = {store_id: index for index, store_id in enumerate(baseline_store_ids)}
    rows = []
    for store_id in candidate_store_ids:
        store = catalog.stores_by_id[store_id]
        best = (-1, -1, -1.0)
        for menu in catalog.menus_by_store.get(store_id, ()):
            if not _eligible(store, menu, filters):
                continue
            family = catalog.families_by_id.get(menu["familyId"], {})
            score = (
                _family_evidence_count(evidence, family),
                lexical.menu_match_counts.get(menu["id"], 0),
                embedding_scores.get(menu["id"], -1.0),
            )
            best = max(best, score)
        core_count, lexical_count, embedding_score = best
        relevance = (
            core_count * 40.0
            + lexical_count * 12.0
            + max(embedding_score, 0.0) * 20.0
            + (8.0 / (baseline_rank[store_id] + 1) if store_id in baseline_rank else 0.0)
        )
        rows.append((store_id, relevance, store))
    rows.sort(key=lambda row: (
        -row[1], -row[2]["recommendationScore"], row[2]["distanceMeters"], row[0],
    ))
    return tuple(row[0] for row in rows)


def _combined_safety(
    baseline: dict[str, bool], addition: dict[str, bool],
) -> dict[str, bool]:
    return {key: baseline[key] or addition[key] for key in baseline}


def evaluate_hybrid_variants(
    *, dataset: dict[str, Any], query: dict[str, Any], structured_call: dict[str, Any],
    embedding_menu_scores: Iterable[tuple[str, float]],
    prepared_catalog: HybridPreparedCatalog | None = None,
) -> dict[str, Any]:
    catalog = prepared_catalog or prepare_hybrid_catalog(
        families=dataset["families"], stores=dataset["stores"], menus=dataset["menus"],
    )
    evidence = structured_call["evidence"]
    d_variant = structured_call["variants"]["C"]
    d_ranking = tuple(d_variant["cutoffs"]["all"]["rankedStoreIds"])
    explicit_menu = (
        bool(evidence.get("menuFamilies"))
        and evidence.get("sources", {}).get("menuFamilies") == "DETERMINISTIC"
    )
    lexical = LexicalCandidateResult((), (), {})
    embedding_menu_ids: tuple[str, ...] = ()
    embedding_store_ids: tuple[str, ...] = ()
    embedding_scores: dict[str, float] = {}
    if not explicit_menu and _core_evidence_count(evidence) >= 2:
        lexical = retrieve_lexical_candidates(
            query_text=query["text"], filters=query["filters"], sort=query["sort"],
            catalog=catalog,
        )
        embedding_menu_ids, embedding_store_ids, embedding_scores = (
            _eligible_embedding_candidates(
                embedding_menu_scores=embedding_menu_scores,
                filters=query["filters"], catalog=catalog,
            )
        )
    e_ranking = merge_application_candidates(
        original_store_ids=d_ranking, supplement_store_ids=lexical.store_ids,
        page_size=None,
    )
    f_ranking = merge_application_candidates(
        original_store_ids=e_ranking, supplement_store_ids=embedding_store_ids,
        page_size=None,
    )
    if query["sort"] == "RECOMMENDED" and not explicit_menu:
        g_ranking = _hybrid_ranking(
            candidate_store_ids=f_ranking, baseline_store_ids=d_ranking,
            evidence=evidence, lexical=lexical, embedding_scores=embedding_scores,
            filters=query["filters"], catalog=catalog,
        )
    else:
        g_ranking = f_ranking
    acceptable_gold = query.get("acceptableGold", query["gold"])
    rankings = {"D": d_ranking, "E": e_ranking, "F": f_ranking, "G": g_ranking}
    variants = {
        variant: {
            "label": {
                "D": "structured-candidates-structured-ranking",
                "E": "D-plus-description-tag-token-candidates",
                "F": "E-plus-existing-large-embedding-candidates",
                "G": "F-candidates-single-hybrid-relevance-ranking",
            }[variant],
            "cutoffs": _variant_cutoffs(ranking, query["gold"], acceptable_gold),
        }
        for variant, ranking in rankings.items()
    }
    forbidden_menus = set(query["gold"].get("forbiddenMenuIds", []))
    forbidden_stores = set(query["gold"].get("forbiddenStoreIds", []))
    baseline_safety = dict(d_variant["safety"])
    lexical_safety = _candidate_safety(
        menu_ids=lexical.menu_ids, store_ids=lexical.store_ids,
        stores=dataset["stores"], menus=dataset["menus"], filters=query["filters"],
        forbidden_menu_ids=forbidden_menus, forbidden_store_ids=forbidden_stores,
    )
    embedding_safety = _candidate_safety(
        menu_ids=embedding_menu_ids, store_ids=embedding_store_ids,
        stores=dataset["stores"], menus=dataset["menus"], filters=query["filters"],
        forbidden_menu_ids=forbidden_menus, forbidden_store_ids=forbidden_stores,
    )
    variants["D"]["safety"] = baseline_safety
    variants["E"]["safety"] = _combined_safety(baseline_safety, lexical_safety)
    variants["F"]["safety"] = _combined_safety(variants["E"]["safety"], embedding_safety)
    variants["G"]["safety"] = variants["F"]["safety"]
    return {
        "schemaVersion": "miriyum-hybrid-search-call-v1",
        "queryId": structured_call["queryId"],
        "repeatIndex": structured_call["repeatIndex"],
        "queryType": structured_call["queryType"],
        "querySubtype": structured_call.get("querySubtype"),
        "goldNegative": structured_call["goldNegative"],
        "evidence": evidence,
        "lexicalCandidateMenuIds": list(lexical.menu_ids),
        "embeddingCandidateMenuIds": list(embedding_menu_ids),
        "variants": variants,
    }


def _majority(
    query_calls: list[dict[str, Any]], variant: str, cutoff: str, field: str,
) -> bool:
    threshold = len(query_calls) // 2 + 1
    return sum(
        bool(call["variants"][variant]["cutoffs"][cutoff][field])
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


def aggregate_hybrid_comparison(calls: list[dict[str, Any]]) -> dict[str, Any]:
    by_query: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for call in calls:
        by_query[call["queryId"]].append(call)

    majority: dict[tuple[str, str, str, str], bool] = {}
    variants: dict[str, Any] = {}
    for variant in HYBRID_VARIANTS:
        cutoff_rows = {}
        for cutoff in STRUCTURED_CUTOFFS:
            row = {}
            for gold in ("strict", "acceptable"):
                successes = 0
                for query_id, query_calls in by_query.items():
                    hit = _majority(
                        query_calls, variant, cutoff, f"{gold}Hit",
                    )
                    majority[(query_id, variant, cutoff, gold)] = hit
                    successes += hit
                call_rows = [
                    call["variants"][variant]["cutoffs"][cutoff]
                    for call in calls
                ]
                row[gold] = {
                    "hit": _binary_summary(successes, len(by_query)),
                    "meanRecall": mean(
                        value[f"{gold}Recall"] for value in call_rows
                    ) if call_rows else 0.0,
                }
            ranking_rows = [
                call["variants"][variant]["cutoffs"][cutoff]["ranking"]
                for call in calls
            ]
            row["ranking"] = {
                key: mean(value[key] for value in ranking_rows)
                for key in ranking_rows[0]
            } if ranking_rows else {}
            cutoff_rows[cutoff] = row
        stability_rows = [
            stability_metrics([
                call["variants"][variant]["cutoffs"]["@20"]["rankedStoreIds"]
                for call in sorted(
                    query_calls, key=lambda value: value["repeatIndex"],
                )
            ])
            for query_calls in by_query.values()
        ]
        variants[variant] = {
            "cutoffs": cutoff_rows,
            "stability": {
                key: mean(value[key] for value in stability_rows)
                for key in stability_rows[0]
            } if stability_rows else {},
        }

    paired_deltas = {}
    for label, left, right in (
        ("DtoE", "D", "E"), ("EtoF", "E", "F"), ("FtoG", "F", "G"),
    ):
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
        groups: dict[str, list[str]] = defaultdict(list)
        for query_id, query_calls in by_query.items():
            value = query_calls[0].get(group_field)
            if value:
                groups[str(value)].append(query_id)
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
                    for variant in HYBRID_VARIANTS
                },
            }
            for group, query_ids in sorted(groups.items())
        }

    by_query_type = grouped_breakdown("queryType")
    by_query_subtype = grouped_breakdown("querySubtype")
    safety: dict[str, Any] = {}
    for variant in HYBRID_VARIANTS:
        negative_query_calls = [
            query_calls for query_calls in by_query.values()
            if query_calls[0]["goldNegative"]
        ]
        safety[variant] = {
            "trueNoAnswerFalsePositives": sum(
                _majority(query_calls, variant, "@20", "strictFalsePositive")
                for query_calls in negative_query_calls
                if query_calls[0].get("querySubtype") == "true_no_answer"
            ),
            "allNegativeFalsePositives": sum(
                _majority(query_calls, variant, "@20", "strictFalsePositive")
                for query_calls in negative_query_calls
            ),
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
                            query_calls, variant, cutoff,
                            f"{gold}FalsePositive",
                        )
                        for query_calls in by_query.values()
                    )
                    for gold in ("strict", "acceptable")
                }
                for cutoff in STRUCTURED_CUTOFFS
            },
        }

    fatal_reasons = []
    for variant in ("E", "F", "G"):
        if (
            safety[variant]["trueNoAnswerFalsePositives"]
            > safety["D"]["trueNoAnswerFalsePositives"]
        ):
            fatal_reasons.append(
                f"{variant}_true_no_answer_false_positive_regression"
            )
        if (
            safety[variant]["allNegativeFalsePositives"]
            > safety["D"]["allNegativeFalsePositives"]
        ):
            fatal_reasons.append(
                f"{variant}_all_negative_false_positive_regression"
            )
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
                for cutoff in ("@8", "@20"):
                    key = f"{gold}{cutoff}"
                    if (
                        alias["variants"][variant][key]["successes"]
                        < alias["variants"]["D"][key]["successes"]
                    ):
                        fatal_reasons.append(
                            f"{variant}_alias_bidirectional_{gold}_{cutoff[1:]}_regression"
                        )

    targets = {
        "acceptable@20": {
            "targetRate": 0.90,
            "actualRate": variants["G"]["cutoffs"]["@20"]["acceptable"]["hit"]["rate"],
        },
        "acceptableAll": {
            "targetRate": 0.95,
            "actualRate": variants["G"]["cutoffs"]["all"]["acceptable"]["hit"]["rate"],
        },
    }
    for target in targets.values():
        target["achieved"] = target["actualRate"] >= target["targetRate"]
    return {
        "schemaVersion": "miriyum-hybrid-search-comparison-v1",
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
        "targets": targets,
        "gate": {
            "passed": not fatal_reasons,
            "fatalReasons": fatal_reasons,
            "actualApplication": False,
            "productionActivationApproved": False,
            "status": "simulated",
        },
    }
