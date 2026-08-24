from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import re
import unicodedata
from typing import Any


GENERIC_REVERSE_DENYLIST = frozenset({"면", "탕", "국", "밥", "메뉴", "음식", "요리", "식사", "세트", "정식", "음료"})
MAX_EXPLICIT_MENU_NAMES = 100


class MatchMode(str, Enum):
    EXACT = "exact"
    FORWARD = "forward"
    REVERSE = "reverse"
    ALIAS = "alias"


@dataclass(frozen=True)
class RetrievalResult:
    one_way_menu_ids: tuple[str, ...]
    bidirectional_menu_ids: tuple[str, ...]
    alias_menu_ids: tuple[str, ...]
    one_way_store_ids: tuple[str, ...]
    bidirectional_store_ids: tuple[str, ...]
    provenance: dict[str, MatchMode]
    matched_modes: dict[str, tuple[MatchMode, ...]]


@dataclass(frozen=True)
class OriginalRetrievalResult:
    menu_ids: tuple[str, ...]
    store_ids: tuple[str, ...]


@dataclass(frozen=True)
class EvidenceRetrievalResult:
    menu_ids: tuple[str, ...]
    store_ids: tuple[str, ...]
    evidence_counts: dict[str, int]
    name_modes: dict[str, MatchMode]


@dataclass(frozen=True)
class PreparedMenu:
    menu: dict[str, Any]
    store: dict[str, Any]
    normalized_name: str
    normalized_fields: tuple[str, ...]
    compact_aliases: frozenset[str]


@dataclass(frozen=True)
class PreparedCatalog:
    menus: tuple[PreparedMenu, ...]


def normalize(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip().lower())


def compact(value: str) -> str:
    return re.sub(r"\s+", "", normalize(value))


def collation_fold(value: str) -> str:
    decomposed = unicodedata.normalize("NFKD", normalize(value))
    return "".join(character for character in decomposed if not unicodedata.combining(character))


def prepare_catalog(
    *, families: list[dict[str, Any]], stores: list[dict[str, Any]],
    menus: list[dict[str, Any]],
) -> PreparedCatalog:
    store_by_id = {store["id"]: store for store in stores}
    family_by_id = {family["id"]: family for family in families}
    prepared = []
    for menu in menus:
        family = family_by_id.get(menu["familyId"], {})
        aliases = {compact(value) for value in family.get("aliases", [])}
        aliases.update(compact(value) for value in family.get("variants", []))
        fields = [
            normalize(menu["name"]), normalize(menu.get("description", "")),
            normalize(menu.get("category", "")),
        ]
        fields.extend(normalize(tag) for tag in menu.get("tags", []))
        prepared.append(PreparedMenu(
            menu=menu,
            store=store_by_id[menu["storeId"]],
            normalized_name=fields[0],
            normalized_fields=tuple(fields),
            compact_aliases=frozenset(aliases),
        ))
    return PreparedCatalog(tuple(prepared))


def reverse_allowed(menu_name: str) -> bool:
    value = normalize(menu_name)
    return len(value) >= 2 and value not in GENERIC_REVERSE_DENYLIST


def current_published_name_source(menu: dict[str, Any]) -> bool:
    """Match the resolver universe: non-retired current PUBLISHED, visibility-neutral."""
    if menu.get("retired", False):
        return False
    versions = menu.get("versions")
    if not versions:
        return True
    published_version = menu.get("publishedVersion")
    return any(
        version.get("version") == published_version
        and version.get("status") == "PUBLISHED"
        for version in versions
    )


def deterministic_remaining_keyword(query: dict[str, Any]) -> str:
    """Remove deterministic synthetic structured spans before literal matching.

    The manifest's region and ambience values form the frozen evaluation vocabulary;
    the price template mirrors RuleInterpreter's accepted ``N원 이하`` span.
    """
    value = query["text"]
    filters = query.get("filters", {})
    spans = [filters.get("region"), filters.get("ambience"), filters.get("category")]
    if filters.get("maxPrice") is not None:
        spans.append(f"{filters['maxPrice']}원 이하")
    for span in sorted((str(span) for span in spans if span), key=len, reverse=True):
        value = re.sub(re.escape(span), " ", value, flags=re.IGNORECASE)
    return normalize(value)


def most_specific_explicit_names(names: set[str]) -> tuple[str, ...]:
    maximal = [
        name for name in names
        if not any(
            name != other
            and len(collation_fold(other)) > len(collation_fold(name))
            and collation_fold(name) in collation_fold(other)
            for other in names
        )
    ]
    return tuple(sorted(maximal, key=lambda value: (-len(value), value))[
        :MAX_EXPLICIT_MENU_NAMES
    ])


def _eligible(store: dict[str, Any], menu: dict[str, Any], filters: dict[str, Any]) -> bool:
    if store["verificationStatus"] != "APPROVED" or store["operationStatus"] == "CLOSED":
        return False
    if menu.get("retired", False) or menu.get("visibility") != "VISIBLE":
        return False
    if filters.get("region") and store["region"] != filters["region"]:
        return False
    if filters.get("ambience") and store.get("ambience") != filters["ambience"]:
        return False
    if filters.get("maxPrice") is not None and menu["price"] > filters["maxPrice"]:
        return False
    if filters.get("category") and menu["category"] != filters["category"]:
        return False
    return True


def _sort_key(menu: dict[str, Any], store: dict[str, Any], mode: MatchMode, sort: str) -> tuple[Any, ...]:
    tier = {MatchMode.EXACT: 0, MatchMode.FORWARD: 1, MatchMode.REVERSE: 2, MatchMode.ALIAS: 3}[mode]
    if sort == "DISTANCE":
        business = (store["distanceMeters"], -store["recommendationScore"])
    elif sort == "RATING":
        business = (-store["rating"], -store["recommendationScore"], store["distanceMeters"])
    else:
        business = (-store["recommendationScore"], store["distanceMeters"])
    return (tier, *business, menu["id"])


def retrieve_original_candidates(
    *, remaining_keyword: str, stores: list[dict[str, Any]], menus: list[dict[str, Any]],
    filters: dict[str, Any], sort: str, guarded_reverse: bool = True,
) -> OriginalRetrievalResult:
    """Mirror the application's pre-LLM literal keyword predicate on the synthetic corpus.

    The SQL predicate applies one LIKE pattern made from the whole remainingKeyword to
    store name, localized region/address, or the current visible menu name. Synthetic
    stores do not contain an address field, so only fields present in the manifest are
    evaluated here.
    """
    keyword = normalize(remaining_keyword)
    if not keyword:
        return OriginalRetrievalResult((), ())
    explicit_names = ({
        normalize(menu.get("name", ""))
        for menu in menus
        if current_published_name_source(menu)
        and reverse_allowed(menu.get("name", ""))
        and collation_fold(menu.get("name", "")) in collation_fold(keyword)
    } if guarded_reverse else set())
    maximal_explicit_names = set(most_specific_explicit_names(explicit_names))
    store_by_id = {store["id"]: store for store in stores}
    rows: list[tuple[int, dict[str, Any], dict[str, Any]]] = []
    for menu in menus:
        store = store_by_id[menu["storeId"]]
        if not _eligible(store, menu, filters):
            continue
        store_name = normalize(store.get("name", ""))
        region = normalize(store.get("region", ""))
        address = normalize(store.get("address", ""))
        menu_name = normalize(menu.get("name", ""))
        if store_name == keyword:
            tier = 0
        elif keyword in store_name:
            tier = 1
        elif keyword in menu_name or menu_name in maximal_explicit_names:
            tier = 2
        elif keyword in region or (address and keyword in address):
            tier = 3
        else:
            continue
        rows.append((tier, menu, store))
    rows.sort(key=lambda row: (row[0], *_sort_key(row[1], row[2], MatchMode.EXACT, sort)[1:]))
    menu_ids = tuple(dict.fromkeys(row[1]["id"] for row in rows))
    store_ids = tuple(dict.fromkeys(row[2]["id"] for row in rows))
    return OriginalRetrievalResult(menu_ids, store_ids)


def merge_application_candidates(
    *, original_store_ids: tuple[str, ...], supplement_store_ids: tuple[str, ...],
    page_size: int | None = 8,
) -> tuple[str, ...]:
    merged = list(dict.fromkeys(original_store_ids))
    if page_size is None or len(merged) < page_size:
        merged.extend(store_id for store_id in supplement_store_ids if store_id not in merged)
    return tuple(merged if page_size is None else merged[:page_size])


def retrieve_candidates(
    *, concepts: list[str], families: list[dict[str, Any]], stores: list[dict[str, Any]],
    menus: list[dict[str, Any]], filters: dict[str, Any], sort: str,
    prepared_catalog: PreparedCatalog | None = None,
) -> RetrievalResult:
    prepared_catalog = prepared_catalog or prepare_catalog(
        families=families, stores=stores, menus=menus,
    )
    concept_values = [normalize(value) for value in concepts if normalize(value)]
    rows: list[tuple[dict[str, Any], dict[str, Any], MatchMode, tuple[MatchMode, ...]]] = []
    alias_ids: list[str] = []
    for prepared in prepared_catalog.menus:
        menu = prepared.menu
        store = prepared.store
        if not _eligible(store, menu, filters):
            continue
        name = prepared.normalized_name
        fields = prepared.normalized_fields
        aliases = prepared.compact_aliases
        modes: set[MatchMode] = set()
        for concept in concept_values:
            if concept == name:
                modes.add(MatchMode.EXACT)
            elif any(concept in field for field in fields):
                modes.add(MatchMode.FORWARD)
            if reverse_allowed(name) and name in concept:
                modes.add(MatchMode.REVERSE)
            if compact(concept) in aliases:
                modes.add(MatchMode.ALIAS)
        if not modes:
            continue
        primary = min(
            (mode for mode in modes if mode != MatchMode.ALIAS),
            key=lambda value: (MatchMode.EXACT, MatchMode.FORWARD, MatchMode.REVERSE).index(value),
            default=MatchMode.ALIAS,
        )
        ordered_modes = tuple(mode for mode in MatchMode if mode in modes)
        rows.append((menu, store, primary, ordered_modes))
        if MatchMode.ALIAS in modes:
            alias_ids.append(menu["id"])
    rows.sort(key=lambda row: _sort_key(row[0], row[1], row[2], sort))
    one_way = tuple(row[0]["id"] for row in rows if row[2] in (MatchMode.EXACT, MatchMode.FORWARD))
    bidirectional = tuple(row[0]["id"] for row in rows if row[2] in (MatchMode.EXACT, MatchMode.FORWARD, MatchMode.REVERSE))

    def store_ids(menu_ids: tuple[str, ...]) -> tuple[str, ...]:
        allowed = set(menu_ids)
        return tuple(dict.fromkeys(row[1]["id"] for row in rows if row[0]["id"] in allowed))

    return RetrievalResult(
        one_way_menu_ids=one_way,
        bidirectional_menu_ids=bidirectional,
        alias_menu_ids=tuple(menu_id for menu_id in (row[0]["id"] for row in rows) if menu_id in set(alias_ids)),
        one_way_store_ids=store_ids(one_way),
        bidirectional_store_ids=store_ids(bidirectional),
        provenance={row[0]["id"]: row[2] for row in rows},
        matched_modes={row[0]["id"]: row[3] for row in rows},
    )


def retrieve_evidence_candidates(
    *, query_text: str, concepts: list[str], families: list[dict[str, Any]],
    stores: list[dict[str, Any]], menus: list[dict[str, Any]],
    filters: dict[str, Any], sort: str,
    prepared_catalog: PreparedCatalog | None = None,
    name_result: RetrievalResult | None = None,
) -> EvidenceRetrievalResult:
    """Simulate attribute-aware retrieval without changing the application predicate."""
    name_result = name_result or retrieve_candidates(
        concepts=concepts, families=families, stores=stores, menus=menus,
        filters=filters, sort=sort, prepared_catalog=prepared_catalog,
    )
    family_by_id = {family["id"]: family for family in families}
    store_by_id = {store["id"]: store for store in stores}
    query_value = compact(query_text)
    core_attributes = ("ingredient", "taste", "method", "broth")
    explicit_terms = {
        compact(str(value))
        for family in families
        for value in (
            family.get("canonical", ""), family.get("baseName", ""),
            *family.get("aliases", []), *family.get("variants", []),
        )
        if compact(str(value)) and reverse_allowed(str(value))
    }
    if any(term in query_value for term in explicit_terms):
        selected = set(name_result.bidirectional_menu_ids)
        return EvidenceRetrievalResult(
            menu_ids=name_result.bidirectional_menu_ids,
            store_ids=name_result.bidirectional_store_ids,
            evidence_counts={menu_id: 0 for menu_id in selected},
            name_modes={
                menu_id: mode for menu_id, mode in name_result.provenance.items()
                if menu_id in selected
            },
        )
    rows: list[tuple[dict[str, Any], dict[str, Any], int, MatchMode | None]] = []
    evidence_counts: dict[str, int] = {}
    for menu in menus:
        store = store_by_id[menu["storeId"]]
        if not _eligible(store, menu, filters):
            continue
        attributes = family_by_id.get(menu["familyId"], {}).get("attributes", {})
        evidence_count = sum(
            bool(compact(str(attributes.get(key, ""))))
            and compact(str(attributes.get(key, ""))) in query_value
            for key in core_attributes
        )
        name_mode = name_result.provenance.get(menu["id"])
        if name_mode is None and evidence_count < 2:
            continue
        evidence_counts[menu["id"]] = evidence_count
        rows.append((menu, store, evidence_count, name_mode))
    rows.sort(key=lambda row: (
        -row[2],
        4 if row[3] is None else {
            MatchMode.EXACT: 0, MatchMode.FORWARD: 1,
            MatchMode.REVERSE: 2, MatchMode.ALIAS: 3,
        }[row[3]],
        *_sort_key(row[0], row[1], row[3] or MatchMode.ALIAS, sort)[1:],
    ))
    menu_ids = tuple(row[0]["id"] for row in rows)
    store_ids = tuple(dict.fromkeys(row[1]["id"] for row in rows))
    return EvidenceRetrievalResult(
        menu_ids=menu_ids,
        store_ids=store_ids,
        evidence_counts=evidence_counts,
        name_modes={menu_id: mode for menu_id, mode in name_result.provenance.items() if menu_id in evidence_counts},
    )
