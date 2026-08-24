from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
import re
from statistics import mean
from typing import Any, Iterable

from .matching import (
    _eligible, compact, deterministic_remaining_keyword,
    merge_application_candidates, normalize,
)
from .metrics import stability_metrics, wilson_interval
from .structured_search import STRUCTURED_CUTOFFS, _candidate_safety, _variant_cutoffs


ACTUAL_FOOD_EVIDENCE_VARIANT = "H"
ACTUAL_FOOD_EVIDENCE_LABEL = "actual-application-predicate-food-evidence-v1"
HYBRID_VARIANTS = ("D", "E", "F", "G", ACTUAL_FOOD_EVIDENCE_VARIANT)
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

# Frozen parity copy of production FoodEvidenceVocabulary food-evidence-v1.
# Candidate matching below intentionally reads no synthetic family IDs/attributes.
_FOOD_VOCABULARY: dict[str, tuple[tuple[str, ...], ...]] = {
    "menuFamilies": (
        ("짬뽕", "해물짬뽕", "불향 해물 짬뽕", "옛날 짬뽕"),
        ("뼈해장국", "뼈다귀 해장국", "뼈다귀해장국"),
        ("잔치국수", "멸치국수", "멸치 육수 국수"),
        ("제육볶음", "제육"), ("닭갈비", "춘천닭갈비"), ("쌀국수", "포"),
        ("불고기", "소불고기"), ("아메리카노", "아아", "아이스 아메리카노"),
        ("김치찌개", "김치찌게"), ("된장찌개", "된장찌게"),
        ("순두부찌개", "순두부"), ("부대찌개", "부대전골"),
        ("갈비탕", "갈비 탕"), ("설렁탕", "설농탕"), ("곰탕", "소고기곰탕"),
        ("삼계탕", "닭백숙"), ("감자탕", "감자 탕"), ("떡볶이", "떡뽁이"),
        ("김밥", "김밥 한줄"), ("비빔밥", "비빔 밥"), ("볶음밥", "볶음 밥"),
        ("카레라이스", "카레밥"), ("돈가스", "돈까스"),
        ("함박스테이크", "함박"), ("파스타", "스파게티"), ("피자", "피짜"),
        ("햄버거", "버거"), ("초밥", "스시"), ("우동", "가락국수"),
        ("라멘", "일본라면"), ("냉면", "물냉면"), ("칼국수", "손칼국수"),
        ("수제비", "손수제비"), ("콩국수", "콩 국수"), ("마라탕", "마라 탕"),
        ("짜장면", "자장면"), ("탕수육",), ("깐풍기", "깐풍치킨"),
        ("샤브샤브", "샤브"), ("월남쌈", "베트남쌈"),
        ("팟타이", "태국볶음면"), ("쭈꾸미볶음", "주꾸미볶음"),
        ("낙지볶음", "낙지 볶음"), ("생선구이", "고등어구이"),
        ("치킨", "후라이드치킨"), ("닭강정", "강정치킨"),
        ("보쌈", "돼지보쌈"), ("족발", "왕족발"),
        ("샐러드", "야채샐러드"), ("팬케이크", "핫케이크"),
    ),
    "ingredients": (
        ("해물", "해산물"), ("돼지뼈", "뼈다귀"), ("멸치",),
        ("돼지고기", "돼지"), ("닭고기", "닭"), ("소고기",), ("원두",),
        ("김치",), ("된장",), ("두부", "순두부"), ("햄",), ("소갈비", "갈비"),
        ("쌀떡", "떡"), ("쌀",), ("나물", "채소", "야채"), ("카레",),
        ("밀", "밀가루"), ("치즈",), ("생선",), ("메밀",), ("콩",),
        ("향신료",), ("춘장",), ("쌀면",), ("쭈꾸미", "주꾸미"), ("낙지",),
    ),
    "tastes": (
        ("칼칼한", "얼큰한"), ("매콤한", "매운"), ("고소한",), ("담백한",),
        ("진한", "진득한"), ("달콤한",), ("쌉싸름한",), ("향긋한",),
        ("얼얼한",), ("새콤한", "상큼한"), ("새콤달콤한",), ("짭짤한",),
    ),
    "broths": (("국물", "국물 있는"), ("자작", "자작한")),
    "methods": (
        ("끓임", "끓인", "끓여"), ("볶음", "볶은"), ("구이", "구운"),
        ("추출",), ("말이", "말아"), ("비빔", "비빈"), ("튀김", "튀긴"),
        ("굽기", "구워"), ("생식", "회"), ("냉조리", "차가운"),
        ("데침", "데친"), ("삶기", "삶은"), ("수제", "손수"), ("직화",),
    ),
    "aromas": (
        ("구수한 향", "구수한향"), ("불향", "직화향"), ("허브향",),
        ("마늘향",), ("후추향",), ("참기름향",), ("생강향",), ("파향",),
        ("된장향",), ("고추향",), ("레몬향",), ("훈연향",), ("들깨향",),
        ("해산물향",), ("곡물향",),
    ),
    "textures": (
        ("부드러운", "보드라운"), ("쫄깃한", "탱글한", "탄력 있는"),
        ("바삭한",), ("아삭한", "사각한"), ("촉촉한",), ("포슬한", "폭신한"),
        ("꾸덕한", "진득한"), ("몽글한",), ("가벼운",), ("도톰한",),
        ("고슬한",), ("매끈한",), ("단단한",),
    ),
    "forms": (("면",), ("탕", "국"), ("밥",)),
}
_ACTUAL_CORE_FIELDS = ("ingredients", "tastes", "broths", "methods", "aromas", "textures")


@dataclass(frozen=True)
class HybridPreparedCatalog:
    families_by_id: dict[str, dict[str, Any]]
    stores_by_id: dict[str, dict[str, Any]]
    menus_by_id: dict[str, dict[str, Any]]
    menus_by_store: dict[str, tuple[dict[str, Any], ...]]
    searchable_tokens_by_menu: dict[str, frozenset[str]]
    searchable_vocabulary: frozenset[str]
    actual_fields_by_menu: dict[str, tuple[str, ...]]
    actual_current_menu_ids: frozenset[str]
    actual_ranking_cache: dict[tuple[Any, ...], tuple[Any, ...]]


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
    families_by_id = {family["id"]: family for family in families}
    for menu in menus:
        menus_by_store[menu["storeId"]].append(menu)
        attributes = families_by_id.get(menu["familyId"], {}).get("attributes", {})
        searchable_tokens[menu["id"]] = frozenset(_tokens(" ".join((
            menu.get("name", ""), menu.get("description", ""),
            *menu.get("tags", []),
            *(str(attributes.get(field, "")) for field in (
                "ingredient", "taste", "broth", "method", "aroma", "texture",
            )),
        ))))
    actual_fields = {
        menu["id"]: _actual_menu_fields(menu) for menu in menus
    }
    actual_current = frozenset(
        menu["id"] for menu in menus if _actual_menu_current(menu)
    )
    return HybridPreparedCatalog(
        families_by_id=families_by_id,
        stores_by_id={store["id"]: store for store in stores},
        menus_by_id={menu["id"]: menu for menu in menus},
        menus_by_store={key: tuple(values) for key, values in menus_by_store.items()},
        searchable_tokens_by_menu=searchable_tokens,
        searchable_vocabulary=frozenset(
            token for tokens in searchable_tokens.values() for token in tokens
            if token not in _LEXICAL_STOPWORDS
        ),
        actual_fields_by_menu=actual_fields,
        actual_current_menu_ids=actual_current,
        actual_ranking_cache={},
    )


def _business_sort_key(menu: dict[str, Any], store: dict[str, Any], sort: str) -> tuple[Any, ...]:
    if sort == "DISTANCE":
        return (store["distanceMeters"], -store["recommendationScore"], menu["id"])
    if sort == "RATING":
        return (-store["rating"], -store["recommendationScore"], store["distanceMeters"], menu["id"])
    return (-store["recommendationScore"], store["distanceMeters"], menu["id"])


def _extract_vocabulary_terms(value: str, field: str) -> tuple[tuple[str, ...], ...]:
    """Mirror production longest-first, non-overlapping extraction per dimension."""
    normalized = normalize(value)
    candidates: list[tuple[int, int, tuple[str, ...], str]] = []
    for aliases in _FOOD_VOCABULARY[field]:
        for alias in aliases:
            start = normalized.find(normalize(alias))
            while start >= 0:
                candidates.append((start, start + len(normalize(alias)), aliases, alias))
                start = normalized.find(normalize(alias), start + 1)
    candidates.sort(key=lambda row: (-(row[1] - row[0]), row[0], row[2][0]))
    occupied = [False] * len(normalized)
    accepted: list[tuple[str, ...]] = []
    for start, end, aliases, _surface in candidates:
        if any(occupied[start:end]):
            continue
        if aliases not in accepted:
            accepted.append(aliases)
        occupied[start:end] = [True] * (end - start)
    return tuple(accepted)


def _resolve_llm_terms(values: Iterable[str], field: str) -> tuple[tuple[str, ...], ...]:
    by_alias = {
        normalize(alias): aliases
        for aliases in _FOOD_VOCABULARY[field]
        for alias in aliases
    }
    return tuple(dict.fromkeys(
        by_alias[normalize(str(value))]
        for value in values
        if normalize(str(value)) in by_alias
    ))


def _actual_evidence(query_text: str, supplied: dict[str, Any]) -> dict[str, Any]:
    deterministic = {
        field: _extract_vocabulary_terms(query_text, field)
        for field in _FOOD_VOCABULARY
    }
    merged: dict[str, Any] = {}
    sources = supplied.get("sources", {})
    for field, terms in deterministic.items():
        if terms:
            merged[field] = terms
        elif sources.get(field) == "LLM":
            merged[field] = _resolve_llm_terms(supplied.get(field, ()), field)
        else:
            merged[field] = ()
    core_count = sum(bool(merged[field]) for field in _ACTUAL_CORE_FIELDS)
    merged["rawFoodSpans"] = (
        ((normalize(query_text),),)
        if merged["menuFamilies"] or core_count >= 2
        else ()
    )
    merged["sources"] = {
        field: ("DETERMINISTIC" if deterministic[field] else "LLM")
        for field in _FOOD_VOCABULARY
        if merged[field]
    }
    return merged


def _actual_menu_fields(menu: dict[str, Any]) -> tuple[str, ...]:
    return tuple(normalize(str(value)) for value in (
        menu.get("name", ""), menu.get("description", ""), menu.get("category", ""),
        *menu.get("secondaryCategories", []), *menu.get("tags", []),
    ))


def _actual_menu_current(menu: dict[str, Any]) -> bool:
    if menu.get("retired", False) or menu.get("visibility") != "VISIBLE":
        return False
    versions = menu.get("versions")
    if not versions:
        return True
    published = menu.get("publishedVersion")
    return any(
        version.get("version") == published and version.get("status") == "PUBLISHED"
        for version in versions
    )


def _contains_any(fields: Iterable[str], groups: Iterable[tuple[str, ...]]) -> bool:
    return any(
        normalize(alias) in field
        for aliases in groups for alias in aliases for field in fields
    )


def _explicit_menu_names(query_text: str, catalog: HybridPreparedCatalog) -> tuple[str, ...]:
    names = {
        normalize(menu.get("name", ""))
        for menu in catalog.menus_by_id.values()
        if menu["id"] in catalog.actual_current_menu_ids
        and normalize(menu.get("name", ""))
        and normalize(menu.get("name", "")) not in _LEXICAL_STOPWORDS
        and normalize(menu.get("name", "")) in normalize(query_text)
    }
    return tuple(
        name for name in sorted(names, key=lambda value: (-len(value), value))
        if not any(name != other and len(other) > len(name) and name in other for other in names)
    )[:100]


def _actual_food_evidence_ranking(
    *, query: dict[str, Any], supplied_evidence: dict[str, Any],
    baseline_store_ids: tuple[str, ...], catalog: HybridPreparedCatalog,
) -> tuple[tuple[str, ...], tuple[str, ...], dict[str, int], dict[str, Any]]:
    evidence_text = deterministic_remaining_keyword(query)
    evidence = _actual_evidence(evidence_text, supplied_evidence)
    cache_key = (
        evidence_text, query["sort"],
        baseline_store_ids,
        tuple(sorted((str(key), repr(value)) for key, value in query["filters"].items())),
        tuple(
            (field, evidence["sources"].get(field), evidence[field])
            for field in _FOOD_VOCABULARY
        ),
    )
    cached = catalog.actual_ranking_cache.get(cache_key)
    if cached is not None:
        menu_ids, store_ids, frozen_scores = cached
        return menu_ids, store_ids, dict(frozen_scores), evidence
    explicit_names = set(_explicit_menu_names(evidence_text, catalog))
    rows: list[tuple[dict[str, Any], dict[str, Any], int]] = []
    menu_scores: dict[str, int] = {}
    for menu in catalog.menus_by_id.values():
        store = catalog.stores_by_id[menu["storeId"]]
        if (
            not _eligible(store, menu, query["filters"])
            or menu["id"] not in catalog.actual_current_menu_ids
        ):
            continue
        fields = catalog.actual_fields_by_menu[menu["id"]]
        name = normalize(menu.get("name", ""))
        deterministic_families = (
            evidence["menuFamilies"]
            if evidence["sources"].get("menuFamilies") == "DETERMINISTIC" else ()
        )
        llm_families = (
            evidence["menuFamilies"]
            if evidence["sources"].get("menuFamilies") == "LLM" else ()
        )
        if name in explicit_names or _contains_any((name,), deterministic_families):
            menu_rank = 3
        elif _contains_any((name,), evidence["rawFoodSpans"]):
            menu_rank = 2
        elif _contains_any(fields, llm_families):
            menu_rank = 1
        else:
            menu_rank = 0
        dimension_count = sum(
            _contains_any(fields, evidence[field]) for field in _ACTUAL_CORE_FIELDS
        )
        if menu_rank == 0 and dimension_count < 2:
            continue
        score = menu_rank * 10 + dimension_count
        menu_scores[menu["id"]] = score
        rows.append((menu, store, score))
    baseline_rank = {
        store_id: index for index, store_id in enumerate(baseline_store_ids)
    }
    rows.sort(key=lambda row: (
        -row[2],
        baseline_rank.get(row[1]["id"], len(baseline_rank)),
        *_business_sort_key(row[0], row[1], query["sort"]),
        row[1]["id"],
    ))
    menu_ids = tuple(row[0]["id"] for row in rows)
    store_ids = tuple(dict.fromkeys(row[1]["id"] for row in rows))
    catalog.actual_ranking_cache[cache_key] = (
        menu_ids, store_ids, tuple(menu_scores.items()),
    )
    return menu_ids, store_ids, menu_scores, evidence


def retrieve_lexical_candidates(
    *, query_text: str, filters: dict[str, Any], sort: str,
    catalog: HybridPreparedCatalog,
) -> LexicalCandidateResult:
    query_tokens = set(informative_query_tokens(query_text, filters=filters))
    compact_query = compact(query_text)
    query_tokens.update(
        token for token in catalog.searchable_vocabulary
        if compact(token) in compact_query
    )
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
    h_menu_ids, h_structured_ranking, h_menu_scores, h_evidence = _actual_food_evidence_ranking(
        query=query, supplied_evidence=evidence,
        baseline_store_ids=d_ranking, catalog=catalog,
    )
    h_ranking = merge_application_candidates(
        original_store_ids=h_structured_ranking,
        supplement_store_ids=d_ranking,
        page_size=None,
    )
    acceptable_gold = query.get("acceptableGold", query["gold"])
    rankings = {
        "D": d_ranking, "E": e_ranking, "F": f_ranking, "G": g_ranking,
        ACTUAL_FOOD_EVIDENCE_VARIANT: h_ranking,
    }
    variants = {
        variant: {
            "label": {
                "D": "structured-candidates-structured-ranking",
                "E": "D-plus-description-tag-token-candidates",
                "F": "E-plus-existing-large-embedding-candidates",
                "G": "F-candidates-single-hybrid-relevance-ranking",
                ACTUAL_FOOD_EVIDENCE_VARIANT: ACTUAL_FOOD_EVIDENCE_LABEL,
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
    h_structured_safety = _candidate_safety(
        menu_ids=h_menu_ids, store_ids=h_ranking,
        stores=dataset["stores"], menus=dataset["menus"], filters=query["filters"],
        forbidden_menu_ids=forbidden_menus, forbidden_store_ids=forbidden_stores,
    )
    variants[ACTUAL_FOOD_EVIDENCE_VARIANT]["safety"] = _combined_safety(
        baseline_safety, h_structured_safety,
    )
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
        "actualFoodEvidence": {
            "variant": ACTUAL_FOOD_EVIDENCE_LABEL,
            "actualApplication": False,
            "actualApplicationPredicate": True,
            "queryEvidenceProvenance": "legacy-structured-checkpoint-replay",
            "queryEvidenceSchemaComplete": False,
            "missingReplayFields": ["aromas", "textures"],
            "vocabularyVersion": "food-evidence-v1",
            "evidence": {
                field: [list(aliases) for aliases in values]
                if field != "sources" else values
                for field, values in h_evidence.items()
            },
            "candidateMenuIds": list(h_menu_ids),
            "menuScores": h_menu_scores,
        },
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
        ("DtoH", "D", ACTUAL_FOOD_EVIDENCE_VARIANT),
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
    for variant in ("E", "F", "G", ACTUAL_FOOD_EVIDENCE_VARIANT):
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
            "actualRate": variants[ACTUAL_FOOD_EVIDENCE_VARIANT]["cutoffs"]["@20"]["acceptable"]["hit"]["rate"],
        },
        "acceptableAll": {
            "targetRate": 0.95,
            "actualRate": variants[ACTUAL_FOOD_EVIDENCE_VARIANT]["cutoffs"]["all"]["acceptable"]["hit"]["rate"],
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
            "variant": ACTUAL_FOOD_EVIDENCE_LABEL,
            "actualApplication": False,
            "actualApplicationPredicate": True,
            "productionActivationApproved": False,
            "status": "actual-predicate-legacy-checkpoint-replay",
        },
    }
