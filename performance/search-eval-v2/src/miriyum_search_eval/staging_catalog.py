from __future__ import annotations

from collections import Counter
from hashlib import sha256
import json
from pathlib import Path
import random
import re
from typing import Any


SCHEMA_VERSION = "miriyum-staging-search-eval-v1"
CORPUS_SOURCE = "backend/scripts/dev-data/search-profile-demo-500-stores.sql"
APPROVED_CORPUS_SHA256 = "5aaf573eb4e09851b30753ed2e4da4669f0c95d2f0ff674471024afff40c37db"
QUERY_COUNTS = {
    "sensory_without_menu": 800,
    "composite_filter": 400,
    "alias_bidirectional": 300,
    "same_menu_ranking": 200,
    "negative_or_ambiguous": 150,
    "generic_or_impossible_defense": 150,
}
PILOT_COUNTS = {
    "sensory_without_menu": 40,
    "composite_filter": 20,
    "alias_bidirectional": 15,
    "same_menu_ranking": 10,
    "negative_or_ambiguous": 8,
    "generic_or_impossible_defense": 7,
}

REGIONS = (
    ("SEOUL", "서울특별시", ("강남", "성수", "연남", "서촌", "잠실", "망원", "을지로", "한남", "여의도", "합정")),
    ("BUSAN", "부산광역시", ("해운대", "광안리", "서면", "남포", "송정", "동래", "전포", "영도", "기장", "센텀")),
    ("DAEGU", "대구광역시", ("동성로", "수성못", "앞산", "칠성", "범어", "반월당", "대봉", "성서", "월성", "팔공산")),
    ("DAEJEON", "대전광역시", ("둔산", "은행", "유성", "도안", "관저", "대흥", "탄방", "노은", "송촌", "문지")),
    ("GWANGJU", "광주광역시", ("충장로", "상무", "양림", "수완", "첨단", "동명", "봉선", "운암", "금남로", "송정")),
)
BRANDS = (
    ("화로정", "KOREAN"), ("바다국수", "KOREAN"),
    ("마라공방", "CHINESE"), ("홍등루", "CHINESE"),
    ("스시하루", "JAPANESE"), ("우동소리", "JAPANESE"),
    ("오븐테이블", "WESTERN"), ("키친로제", "WESTERN"),
    ("사이공정원", "ASIAN"), ("모닝브루", "CAFE_BAKERY"),
)
CATEGORY_LABELS = {
    "KOREAN": "한식", "CHINESE": "중식", "JAPANESE": "일식",
    "WESTERN": "양식", "ASIAN": "아시아 음식", "CAFE_BAKERY": "카페",
}


def _sql_values_block(source: str) -> str:
    marker = "INSERT INTO search_fixture_menu_templates VALUES"
    start = source.find(marker)
    if start < 0:
        raise ValueError("staging menu template insert is missing")
    start += len(marker)
    quoted = False
    index = start
    while index < len(source):
        char = source[index]
        if char == "'":
            if quoted and index + 1 < len(source) and source[index + 1] == "'":
                index += 2
                continue
            quoted = not quoted
        elif char == ";" and not quoted:
            return source[start:index]
        index += 1
    raise ValueError("staging menu template insert is unterminated")


def _tuple_fields(block: str) -> list[list[str]]:
    rows: list[list[str]] = []
    depth = 0
    quoted = False
    row_start: int | None = None
    index = 0
    while index < len(block):
        char = block[index]
        if char == "'":
            if quoted and index + 1 < len(block) and block[index + 1] == "'":
                index += 2
                continue
            quoted = not quoted
        elif not quoted and char == "(":
            if depth == 0:
                row_start = index + 1
            depth += 1
        elif not quoted and char == ")":
            depth -= 1
            if depth == 0 and row_start is not None:
                rows.append(_split_fields(block[row_start:index]))
                row_start = None
        index += 1
    if quoted or depth != 0:
        raise ValueError("staging menu template tuples are malformed")
    return rows


def _split_fields(row: str) -> list[str]:
    fields: list[str] = []
    quoted = False
    start = 0
    index = 0
    while index < len(row):
        char = row[index]
        if char == "'":
            if quoted and index + 1 < len(row) and row[index + 1] == "'":
                index += 2
                continue
            quoted = not quoted
        elif char == "," and not quoted:
            fields.append(row[start:index].strip())
            start = index + 1
        index += 1
    fields.append(row[start:].strip())
    return fields


def _sql_value(value: str) -> str | int | None:
    if value == "NULL":
        return None
    if re.fullmatch(r"[0-9]+", value):
        return int(value)
    if len(value) >= 2 and value[0] == value[-1] == "'":
        return value[1:-1].replace("''", "'")
    raise ValueError(f"unsupported staging SQL literal: {value}")


def _menu_templates(sql_text: str) -> list[dict[str, Any]]:
    names = (
        "categoryCode", "menuSlot", "menuName", "description", "price",
        "primaryCategoryCode", "menuFamily", "alias", "ingredient", "taste",
        "broth", "method", "aroma", "texture", "form",
    )
    result = []
    for fields in _tuple_fields(_sql_values_block(sql_text)):
        if len(fields) != len(names):
            raise ValueError("staging menu template field count is invalid")
        result.append(dict(zip(names, map(_sql_value, fields), strict=True)))
    if len(result) != 60:
        raise ValueError(f"expected 60 staging menu templates, found {len(result)}")
    return result


def _stores() -> list[dict[str, Any]]:
    stores = []
    for number in range(1, 501):
        region_index = (number - 1) // 100
        neighborhood_index = ((number - 1) % 100) // 10
        brand_index = (number - 1) % 10
        region, city, neighborhoods = REGIONS[region_index]
        brand, category = BRANDS[brand_index]
        neighborhood = neighborhoods[neighborhood_index]
        prefix = "" if region_index == 0 else f"{city[:2]} "
        stores.append({
            "id": str(8_900_000 + number),
            "fixtureNumber": number,
            "region": region,
            "city": city,
            "neighborhood": neighborhood,
            "brand": brand,
            "categoryCode": category,
            "name": f"{prefix}{neighborhood} {brand}",
        })
    return stores


def _menus(stores: list[dict[str, Any]], templates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_category_slot = {
        (template["categoryCode"], template["menuSlot"]): template
        for template in templates
    }
    menus = []
    for store in stores:
        for slot in range(1, 11):
            template = by_category_slot[(store["categoryCode"], slot)]
            menus.append({
                "id": str(89_000_000 + (store["fixtureNumber"] - 1) * 10 + slot),
                "storeId": store["id"],
                "categoryCode": store["categoryCode"],
                "menuSlot": slot,
                "menuName": template["menuName"],
                "menuFamily": template["menuFamily"],
            })
    return menus


def _request_variant(index: int) -> str:
    times = ("오늘 점심에", "오늘 저녁에", "주말 점심에", "주말 저녁에", "비 오는 날", "쌀쌀한 날", "더운 날", "퇴근 후", "한가한 오후에", "늦은 점심에", "운동 후")
    tones = ("먹기 좋은 곳 알려줘", "파는 매장 찾아줘", "괜찮은 곳 추천해줘", "어디에서 먹을 수 있어", "한 끼로 먹고 싶어", "지금 생각나는 곳 찾아줘", "잘하는 매장 보여줘", "메뉴로 추천해줘")
    moods = ("부담 없이", "든든하게", "따뜻하게", "가볍게", "천천히", "푸짐하게", "깔끔하게", "편하게", "제대로", "맛있게")
    tone_index = (index // len(times)) % len(tones)
    mood_index = (index // (len(times) * len(tones))) % len(moods)
    return f"{times[index % len(times)]} {moods[mood_index]} {tones[tone_index]}"


def _gold_for_templates(
    stores: list[dict[str, Any]], templates: list[dict[str, Any]],
    *, template_filter: Any, region: str | None = None,
) -> list[str]:
    categories = {
        template["categoryCode"] for template in templates if template_filter(template)
    }
    return [
        store["id"] for store in stores
        if store["categoryCode"] in categories and (region is None or store["region"] == region)
    ]


def _queries(
    stores: list[dict[str, Any]], templates: list[dict[str, Any]], seed: int,
) -> list[dict[str, Any]]:
    rng = random.Random(seed)
    shuffled_templates = list(templates)
    shuffled_stores = list(stores)
    rng.shuffle(shuffled_templates)
    rng.shuffle(shuffled_stores)
    queries: list[dict[str, Any]] = []

    def add(query_type: str, text: str, gold: list[str], *, negative: bool = False,
            expected_order: list[str] | None = None,
            filters: dict[str, Any] | None = None, match_mode: str | None = None,
            match_expression: str | None = None,
            expected_top: str | None = None,
            expected_pairs: list[list[str]] | None = None) -> None:
        queries.append({
            "id": f"staging-query-{len(queries) + 1:04d}",
            "queryType": query_type,
            "text": text,
            "goldStoreIds": sorted(set(gold), key=int),
            "expectedOrderedStoreIds": expected_order or [],
            "goldNegative": negative,
            "filters": filters or {},
            "matchMode": match_mode,
            "matchExpression": match_expression,
            "expectedTopStoreId": expected_top,
            "expectedBeforeStorePairs": expected_pairs or [],
        })

    for index in range(QUERY_COUNTS["sensory_without_menu"]):
        template = shuffled_templates[index % len(shuffled_templates)]
        attributes = [
            template[key] for key in ("ingredient", "taste", "broth", "method", "aroma", "texture")
            if template[key]
        ]
        selected = attributes[:3] if len(attributes) >= 3 else attributes
        text = f"{' '.join(map(str, selected))} 음식 {_request_variant(index)}"
        gold = _gold_for_templates(
            stores, templates,
            template_filter=lambda candidate, selected=tuple(selected): all(
                value in candidate.values() for value in selected
            ),
        )
        add("sensory_without_menu", text, gold)

    for index in range(QUERY_COUNTS["composite_filter"]):
        store = shuffled_stores[index]
        template = next(
            value for value in templates
            if value["categoryCode"] == store["categoryCode"]
            and value["menuSlot"] == index % 10 + 1
        )
        text = (
            f"{store['city']} {store['neighborhood']} {store['brand']}에서 "
            f"{template['price']}원 이하 {CATEGORY_LABELS[store['categoryCode']]} "
            f"{template['menuFamily']} 찾아줘"
        )
        add("composite_filter", text, [store["id"]], filters={
            "region": store["region"],
            "categoryCode": store["categoryCode"],
            "maximumPrice": template["price"],
        })

    aliases = [template for template in shuffled_templates if template["alias"]]
    match_modes = ("exact", "forward", "reverse", "alias")
    for index in range(QUERY_COUNTS["alias_bidirectional"]):
        template = aliases[index % len(aliases)]
        region, city, _ = REGIONS[index % len(REGIONS)]
        match_mode = match_modes[index % len(match_modes)]
        expression = {
            "exact": template["menuName"],
            "forward": f"정통 {template['menuName']} 한 그릇",
            "reverse": template["menuFamily"],
            "alias": template["alias"],
        }[match_mode]
        text = f"{city}에서 {expression} {_request_variant(index + 800)}"
        template_filter = {
            "exact": lambda candidate, name=template["menuName"]: candidate["menuName"] == name,
            "forward": lambda candidate, name=template["menuName"]: candidate["menuName"] == name,
            "reverse": lambda candidate, family=template["menuFamily"]: candidate["menuFamily"] == family,
            "alias": lambda candidate, alias=template["alias"]: candidate["alias"] == alias,
        }[match_mode]
        gold = _gold_for_templates(
            stores, templates, template_filter=template_filter, region=region,
        )
        add(
            "alias_bidirectional", text, gold, filters={"region": region},
            match_mode=match_mode, match_expression=expression,
        )

    for index in range(QUERY_COUNTS["same_menu_ranking"]):
        store = shuffled_stores[index + 200]
        template = next(
            value for value in templates
            if value["categoryCode"] == store["categoryCode"]
            and value["menuSlot"] == (index * 3) % 10 + 1
        )
        same_menu_categories = {
            candidate["categoryCode"] for candidate in templates
            if candidate["menuFamily"] == template["menuFamily"]
        }
        same_menu_stores = [
            candidate["id"] for candidate in stores
            if candidate["categoryCode"] in same_menu_categories
        ]
        competitors = [store_id for store_id in same_menu_stores if store_id != store["id"]][:3]
        text = f"{store['name']}의 {template['menuFamily']} 같은 메뉴 파는 곳 중 가장 관련 있는 매장"
        add(
            "same_menu_ranking", text, same_menu_stores, expected_order=[store["id"]],
            expected_top=store["id"], expected_pairs=[[store["id"], other] for other in competitors],
        )

    subjects = ("노트북 충전", "우산 수선", "영화 예매", "택배 조회", "자동차 세차")
    situations = ("오늘 가능한 곳", "가까운 곳", "예약하고 싶어", "가격 알려줘", "지금 열었어", "추천해줘")
    qualifiers = ("조용하게", "빠르게", "온라인으로", "아이와 함께", "퇴근 후")
    for index in range(QUERY_COUNTS["negative_or_ambiguous"]):
        subject = subjects[index % 5]
        situation = situations[(index // 5) % 6]
        qualifier = qualifiers[(index // 30) % 5]
        add("negative_or_ambiguous", f"{subject} {situation} {qualifier}", [], negative=True)

    generic = ("면", "탕", "국", "밥", "메뉴")
    impossible = ("카페에서 짬뽕", "한식집 초밥", "중식집 팬케이크", "일식집 마라탕", "양식집 잔치국수")
    requests = ("아무 데나", "가장 싼 곳", "멀어도 괜찮아", "이름 상관없이", "조건 없이", "대충 찾아줘")
    for index in range(QUERY_COUNTS["generic_or_impossible_defense"]):
        text = f"{generic[index % 5]} {impossible[(index // 5) % 5]} {requests[(index // 25) % 6]}"
        add("generic_or_impossible_defense", text, [], negative=True)

    text_counts = Counter(query["text"] for query in queries)
    duplicates = [text for text, count in text_counts.items() if count > 1]
    if duplicates:
        raise ValueError(f"staging query templates produced duplicate text: {duplicates[:3]}")
    return queries


def dataset_fingerprint(dataset: dict[str, Any]) -> str:
    material = json.loads(json.dumps(dataset, ensure_ascii=False))
    material.get("metadata", {}).pop("datasetSha256", None)
    encoded = json.dumps(material, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256(encoded).hexdigest()


def generate_staging_dataset(seed_sql: Path, *, seed: int) -> dict[str, Any]:
    source_bytes = seed_sql.read_bytes()
    sql_text = source_bytes.decode("utf-8")
    templates = _menu_templates(sql_text)
    stores = _stores()
    menus = _menus(stores, templates)
    dataset = {
        "metadata": {
            "schemaVersion": SCHEMA_VERSION,
            "seed": seed,
            "corpusSource": CORPUS_SOURCE,
            "corpusSourceSha256": sha256(source_bytes).hexdigest(),
        },
        "stores": stores,
        "menuTemplates": templates,
        "menus": menus,
        "queries": _queries(stores, templates, seed),
    }
    dataset["metadata"]["datasetSha256"] = dataset_fingerprint(dataset)
    return dataset


def validate_staging_dataset(dataset: dict[str, Any]) -> dict[str, Any]:
    queries = dataset.get("queries", [])
    store_ids = {store.get("id") for store in dataset.get("stores", [])}
    store_id_list = [store.get("id") for store in dataset.get("stores", [])]
    menu_id_list = [menu.get("id") for menu in dataset.get("menus", [])]
    query_ids = [query.get("id") for query in queries]
    query_texts = [query.get("text") for query in queries]
    gold_ids = [store_id for query in queries for store_id in query.get("goldStoreIds", [])]
    public_id = re.compile(r"^[1-9][0-9]*$")
    contradictory = sum(
        bool(query.get("goldNegative")) == bool(query.get("goldStoreIds"))
        for query in queries
    )
    leakage = sum(
        any(store_id in str(query.get("text", "")) for store_id in query.get("goldStoreIds", []))
        for query in queries
    )
    result = {
        "duplicateQueryIds": len(query_ids) - len(set(query_ids)),
        "duplicateQueryTexts": len(query_texts) - len(set(query_texts)),
        "duplicateStoreIds": len(store_id_list) - len(set(store_id_list)),
        "duplicateMenuIds": len(menu_id_list) - len(set(menu_id_list)),
        "invalidGoldPublicIds": sum(not isinstance(value, str) or not public_id.fullmatch(value) for value in gold_ids),
        "orphanGoldStoreIds": sum(value not in store_ids for value in gold_ids),
        "contradictoryGoldLabels": contradictory,
        "targetLeakageFindings": leakage,
        "invalidStoreIds": sum(
            not isinstance(value, str) or not public_id.fullmatch(value)
            or not 8_900_001 <= int(value) <= 8_900_500
            for value in store_id_list
        ),
        "invalidMenuIds": sum(
            not isinstance(value, str) or not public_id.fullmatch(value)
            or not 89_000_001 <= int(value) <= 89_005_000
            for value in menu_id_list
        ),
        "orphanMenuStoreIds": sum(menu.get("storeId") not in store_ids for menu in dataset.get("menus", [])),
        "invalidExpectedOrderIds": sum(
            store_id not in set(query.get("goldStoreIds", []))
            for query in queries for store_id in query.get("expectedOrderedStoreIds", [])
        ),
        "invalidExpectedTopIds": sum(
            query.get("expectedTopStoreId") is not None
            and query.get("expectedTopStoreId") not in set(query.get("goldStoreIds", []))
            for query in queries
        ),
        "invalidExpectedPairIds": sum(
            not isinstance(pair, list) or len(pair) != 2
            or any(store_id not in set(query.get("goldStoreIds", [])) for store_id in pair)
            for query in queries for pair in query.get("expectedBeforeStorePairs", [])
        ),
        "invalidQuerySchema": sum(
            not isinstance(query.get("id"), str)
            or not isinstance(query.get("text"), str)
            or not isinstance(query.get("goldStoreIds"), list)
            or not isinstance(query.get("filters"), dict)
            or not isinstance(query.get("goldNegative"), bool)
            for query in queries
        ),
        "datasetFingerprintMismatch": (
            dataset.get("metadata", {}).get("datasetSha256") != dataset_fingerprint(dataset)
        ),
        "corpusSourceMismatch": (
            dataset.get("metadata", {}).get("corpusSource") != CORPUS_SOURCE
            or dataset.get("metadata", {}).get("corpusSourceSha256") != APPROVED_CORPUS_SHA256
        ),
        "errors": [],
    }
    if len(dataset.get("stores", [])) != 500:
        result["errors"].append("store_count")
    if len(dataset.get("menus", [])) != 5_000:
        result["errors"].append("menu_count")
    if len(queries) != 2_000:
        result["errors"].append("query_count")
    if Counter(query.get("queryType") for query in queries) != Counter(QUERY_COUNTS):
        result["errors"].append("query_distribution")
    for key in result:
        if key != "errors" and result[key]:
            result["errors"].append(key)
    return result


def staging_pilot_queries(queries: list[dict[str, Any]], *, seed: int) -> list[dict[str, Any]]:
    selected = []
    for offset, (query_type, count) in enumerate(PILOT_COUNTS.items()):
        candidates = [query for query in queries if query.get("queryType") == query_type]
        random.Random(seed + offset * 10_007).shuffle(candidates)
        if len(candidates) < count:
            raise ValueError(f"not enough staging queries for {query_type}")
        selected.extend(candidates[:count])
    return sorted(selected, key=lambda query: query["id"])
