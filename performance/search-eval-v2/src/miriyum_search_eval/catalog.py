from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from hashlib import sha256
import json
import random
from typing import Any


REGIONS = (
    "SEOUL_GANGNAM", "SEOUL_MAPO", "SEOUL_JONGNO", "SEOUL_SONGPA",
    "BUSAN_HAEUNDAE", "BUSAN_SUYEONG", "DAEGU_JUNG", "INCHEON_YEONSU",
    "GWANGJU_BUK", "DAEJEON_YUSEONG", "ULSAN_NAM", "SEJONG",
    "GYEONGGI_SUWON", "GYEONGGI_SEONGNAM", "GANGWON_CHUNCHEON",
    "CHUNGBUK_CHEONGJU", "JEJU_JEJU",
)
AMBIENCES = ("조용한", "활기찬", "가족형", "혼밥", "데이트", "전통적인")
PRICE_BANDS = ((6_000, 12_000), (10_000, 18_000), (15_000, 28_000), (25_000, 45_000))
STYLES = (
    ("", "기본"), ("고소한 ", "고소한"), ("칼칼한 ", "칼칼한"),
    ("담백한 ", "담백한"), ("수제 ", "수제"), ("직화 ", "불향"),
)
AROMAS = ("구수한 향", "불향", "허브향", "마늘향", "후추향", "참기름향", "생강향", "파향", "된장향", "고추향", "레몬향", "훈연향", "들깨향", "해산물향", "곡물향")
TEXTURES = ("부드러운", "쫄깃한", "바삭한", "아삭한", "촉촉한", "포슬한", "탱글한", "꾸덕한", "담백한", "진득한", "몽글한", "사각한", "보드라운", "탄력 있는", "가벼운", "도톰한", "고슬한", "매끈한", "폭신한", "단단한")
BASE_DISHES = (
    ("짬뽕", "해물", "얼큰한", "끓임", "국물", "NOODLE", ("해물짬뽕",)),
    ("뼈해장국", "돼지뼈", "진한", "끓임", "국물", "SOUP", ("뼈다귀 해장국",)),
    ("잔치국수", "멸치", "담백한", "끓임", "국물", "NOODLE", ("멸치국수",)),
    ("제육볶음", "돼지고기", "매콤한", "볶음", "없음", "KOREAN", ("제육",)),
    ("닭갈비", "닭고기", "매콤한", "볶음", "없음", "KOREAN", ("춘천닭갈비",)),
    ("쌀국수", "소고기", "향긋한", "끓임", "국물", "NOODLE", ("포",)),
    ("불고기", "소고기", "달콤한", "구이", "없음", "KOREAN", ("소불고기",)),
    ("아메리카노", "원두", "쌉싸름한", "추출", "없음", "CAFE", ("아아", "아이스 아메리카노")),
    ("김치찌개", "김치", "얼큰한", "끓임", "국물", "STEW", ("김치찌게",)),
    ("된장찌개", "된장", "구수한", "끓임", "국물", "STEW", ("된장찌게",)),
    ("순두부찌개", "두부", "얼큰한", "끓임", "국물", "STEW", ("순두부",)),
    ("부대찌개", "햄", "매콤한", "끓임", "국물", "STEW", ("부대전골",)),
    ("갈비탕", "소갈비", "진한", "끓임", "국물", "SOUP", ("갈비 탕",)),
    ("설렁탕", "소고기", "담백한", "끓임", "국물", "SOUP", ("설농탕",)),
    ("곰탕", "소고기", "진한", "끓임", "국물", "SOUP", ("소고기곰탕",)),
    ("삼계탕", "닭고기", "담백한", "끓임", "국물", "SOUP", ("닭백숙",)),
    ("감자탕", "돼지뼈", "얼큰한", "끓임", "국물", "SOUP", ("감자 탕",)),
    ("떡볶이", "쌀떡", "매콤한", "볶음", "자작", "SNACK", ("떡뽁이",)),
    ("김밥", "쌀", "담백한", "말이", "없음", "SNACK", ("김밥 한줄",)),
    ("비빔밥", "나물", "매콤한", "비빔", "없음", "RICE", ("비빔 밥",)),
    ("볶음밥", "쌀", "고소한", "볶음", "없음", "RICE", ("볶음 밥",)),
    ("카레라이스", "카레", "향긋한", "끓임", "자작", "RICE", ("카레밥",)),
    ("돈가스", "돼지고기", "고소한", "튀김", "없음", "WESTERN", ("돈까스",)),
    ("함박스테이크", "소고기", "진한", "구이", "없음", "WESTERN", ("함박",)),
    ("파스타", "밀", "고소한", "볶음", "자작", "WESTERN", ("스파게티",)),
    ("피자", "치즈", "고소한", "굽기", "없음", "WESTERN", ("피짜",)),
    ("햄버거", "소고기", "진한", "굽기", "없음", "WESTERN", ("버거",)),
    ("초밥", "생선", "담백한", "생식", "없음", "JAPANESE", ("스시",)),
    ("우동", "밀", "담백한", "끓임", "국물", "NOODLE", ("가락국수",)),
    ("라멘", "돼지고기", "진한", "끓임", "국물", "NOODLE", ("일본라면",)),
    ("냉면", "메밀", "새콤한", "냉조리", "국물", "NOODLE", ("물냉면",)),
    ("칼국수", "밀", "담백한", "끓임", "국물", "NOODLE", ("손칼국수",)),
    ("수제비", "밀", "구수한", "끓임", "국물", "NOODLE", ("손수제비",)),
    ("콩국수", "콩", "고소한", "냉조리", "국물", "NOODLE", ("콩 국수",)),
    ("마라탕", "향신료", "얼얼한", "끓임", "국물", "CHINESE", ("마라 탕",)),
    ("짜장면", "춘장", "달콤한", "볶음", "자작", "CHINESE", ("자장면",)),
    ("탕수육", "돼지고기", "새콤달콤한", "튀김", "없음", "CHINESE", ("탕수육",)),
    ("깐풍기", "닭고기", "매콤한", "튀김", "없음", "CHINESE", ("깐풍치킨",)),
    ("샤브샤브", "소고기", "담백한", "데침", "국물", "ASIAN", ("샤브",)),
    ("월남쌈", "채소", "상큼한", "말이", "없음", "ASIAN", ("베트남쌈",)),
    ("팟타이", "쌀면", "새콤달콤한", "볶음", "없음", "ASIAN", ("태국볶음면",)),
    ("쭈꾸미볶음", "쭈꾸미", "매콤한", "볶음", "없음", "SEAFOOD", ("주꾸미볶음",)),
    ("낙지볶음", "낙지", "매콤한", "볶음", "없음", "SEAFOOD", ("낙지 볶음",)),
    ("생선구이", "생선", "담백한", "구이", "없음", "SEAFOOD", ("고등어구이",)),
    ("치킨", "닭고기", "고소한", "튀김", "없음", "CHICKEN", ("후라이드치킨",)),
    ("닭강정", "닭고기", "달콤한", "튀김", "없음", "CHICKEN", ("강정치킨",)),
    ("보쌈", "돼지고기", "담백한", "삶기", "없음", "KOREAN", ("돼지보쌈",)),
    ("족발", "돼지고기", "짭짤한", "삶기", "없음", "KOREAN", ("왕족발",)),
    ("샐러드", "채소", "상큼한", "생식", "없음", "SALAD", ("야채샐러드",)),
    ("팬케이크", "밀", "달콤한", "굽기", "없음", "DESSERT", ("핫케이크",)),
)


@dataclass(frozen=True)
class DatasetConfig:
    seed: int
    schema_version: str


def _family_rows() -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for base_index, dish in enumerate(BASE_DISHES):
        name, ingredient, taste, method, broth, category, aliases = dish
        for style_index, (prefix, style_taste) in enumerate(STYLES):
            canonical = f"{prefix}{name}".strip()
            family_id = f"family-{base_index * len(STYLES) + style_index:03d}"
            variants = [canonical, f"집밥 {canonical}", f"{canonical} 한상", f"{canonical} 스페셜"]
            if style_index == 0 and name == "짬뽕":
                variants = ["짬뽕", "불향 해물 짬뽕", "옛날 짬뽕", "매운 해물 짬뽕"]
            elif style_index == 0 and name == "뼈해장국":
                variants = ["뼈해장국", "뼈다귀 해장국", "옛날식 뼈해장국", "뼈다귀해장국 특선"]
            elif style_index == 0 and name == "잔치국수":
                variants = ["잔치국수", "멸치국수", "옛날 잔치국수", "멸치 육수 국수"]
            family_index = base_index * len(STYLES) + style_index
            rows.append({
                "id": family_id,
                "canonical": canonical,
                "baseName": name,
                "aliases": sorted(set((*aliases, name.replace(" ", "")))),
                "variants": variants,
                "attributes": {
                    "ingredient": ingredient,
                    "taste": style_taste if style_index else taste,
                    "method": method,
                    "broth": broth,
                    "category": category,
                    "aroma": AROMAS[family_index % len(AROMAS)],
                    "texture": TEXTURES[family_index // len(AROMAS)],
                },
            })
    return rows


def _stores(rng: random.Random) -> list[dict[str, Any]]:
    stores = []
    categories = ("KOREAN", "CHINESE", "JAPANESE", "WESTERN", "CAFE", "ASIAN")
    for index in range(500):
        band = PRICE_BANDS[index % len(PRICE_BANDS)]
        stores.append({
            "id": f"store-{index:03d}",
            "name": f"합성 {REGIONS[index % len(REGIONS)]} 식당 {index + 1}",
            "region": REGIONS[index % len(REGIONS)],
            "category": categories[index % len(categories)],
            "ambience": AMBIENCES[(index * 5) % len(AMBIENCES)],
            "priceBand": {"min": band[0], "max": band[1]},
            "distanceMeters": 80 + ((index * 137) % 8_000),
            "rating": round(3.0 + ((index * 17) % 21) / 10, 1),
            "recommendationScore": (index * 43) % 1_000,
            "verificationStatus": "REJECTED" if index % 17 == 0 else "APPROVED",
            "operationStatus": "CLOSED" if index % 10 == 0 else ("BREAK" if index % 9 == 0 else "OPEN"),
            "coordinates": {"lat": round(33.2 + rng.random() * 5.2, 6), "lng": round(126.1 + rng.random() * 3.4, 6)},
        })
    return stores


def _menus(families: list[dict[str, Any]], stores: list[dict[str, Any]]) -> list[dict[str, Any]]:
    menus = []
    for index in range(5_000):
        store_index = index // 10
        family_index = (index * 37 + store_index * 11) % len(families)
        family = families[family_index]
        variant_index = (index // len(families)) % 4
        name = family["variants"][variant_index]
        private = index % 13 == 0
        retired = index % 29 == 0
        current_version = 2 if index % 4 == 0 else 1
        versions = []
        if current_version == 2:
            versions.append({
                "version": 1,
                "name": f"옛날 {name}",
                "status": "SUPERSEDED",
                "visibility": "VISIBLE",
            })
        versions.append({
            "version": current_version,
            "name": name,
            "status": "PUBLISHED",
            "visibility": "PRIVATE" if private else "VISIBLE",
        })
        if index % 11 == 0:
            versions.append({
                "version": current_version + 1,
                "name": f"비공개 시험 {name}",
                "status": "DRAFT",
                "visibility": "PRIVATE",
            })
        base_price = stores[store_index]["priceBand"]["min"]
        menus.append({
            "id": f"menu-{index:04d}",
            "storeId": stores[store_index]["id"],
            "familyId": family["id"],
            "name": name,
            "description": (
                f"{family['attributes']['ingredient']} 재료를 {family['attributes']['method']} 방식으로 만든 "
                f"{family['attributes']['taste']} 합성 메뉴"
            ),
            "category": family["attributes"]["category"],
            "tags": [family["attributes"]["taste"], family["attributes"]["ingredient"], family["attributes"]["method"]],
            "price": base_price + (index % 7) * 1_000,
            "retired": retired,
            "visibility": "PRIVATE" if private else "VISIBLE",
            "publishedVersion": current_version,
            "versions": versions,
        })
    return menus


def _eligible(store: dict[str, Any], menu: dict[str, Any]) -> bool:
    return (
        store["verificationStatus"] == "APPROVED"
        and store["operationStatus"] != "CLOSED"
        and not menu["retired"]
        and menu["visibility"] == "VISIBLE"
    )


def _gold(family_ids: set[str], menus: list[dict[str, Any]], store_by_id: dict[str, dict[str, Any]], filters: dict[str, Any] | None = None) -> dict[str, Any]:
    filters = filters or {}
    eligible = []
    for menu in menus:
        store = store_by_id[menu["storeId"]]
        if menu["familyId"] not in family_ids or not _eligible(store, menu):
            continue
        if filters.get("region") and store["region"] != filters["region"]:
            continue
        if filters.get("ambience") and store["ambience"] != filters["ambience"]:
            continue
        if filters.get("maxPrice") is not None and menu["price"] > filters["maxPrice"]:
            continue
        eligible.append((menu, store))
    eligible.sort(key=lambda pair: (-pair[1]["recommendationScore"], pair[1]["distanceMeters"], pair[0]["id"]))
    return {
        "familyIds": sorted(family_ids),
        "menuIds": [menu["id"] for menu, _ in eligible],
        "storeIds": list(dict.fromkeys(store["id"] for _, store in eligible)),
        "negative": not eligible,
        "provenance": [{"familyId": value, "source": "fixed-dictionary"} for value in sorted(family_ids)],
        "forbiddenMenuIds": [],
        "forbiddenStoreIds": [],
    }


def _queries(families: list[dict[str, Any]], stores: list[dict[str, Any]], menus: list[dict[str, Any]]) -> list[dict[str, Any]]:
    store_by_id = {store["id"]: store for store in stores}
    eligible_menus = [menu for menu in menus if _eligible(store_by_id[menu["storeId"]], menu)]
    invalid_menus = [menu for menu in menus if not _eligible(store_by_id[menu["storeId"]], menu)]
    queries: list[dict[str, Any]] = []
    sensory_templates = (
        "{taste} 맛에 {ingredient} 들어가고 {method} 방식인 음식 추천해줘",
        "{ingredient} 재료로 만든 {taste} {broth} 음식이 먹고 싶어",
        "오늘은 {method} 요리 중 {taste} 맛 나는 걸 찾고 있어",
        "{broth} 있는 {ingredient} 요리인데 {taste} 느낌이면 좋아",
        "속 편하게 {ingredient} 들어간 {method} 음식 찾아줘",
        "{taste} 풍미와 {method} 조리가 어울리는 음식 있을까",
        "{ingredient} 중심의 {broth} 요리를 골라줘",
        "따뜻하게 먹는 {method} 음식 중 {taste} 쪽으로 보여줘",
        "혼자 먹기 좋은 {ingredient} {method} 요리 추천",
        "비 오는 날 어울리는 {taste} {broth} 음식",
        "재료는 {ingredient}, 조리는 {method}, 맛은 {taste}인 걸 원해",
        "{taste} 하지만 부담 적은 {ingredient} 음식 찾아줘",
        "국물 여부는 {broth}이고 {ingredient}가 중심인 메뉴",
        "{method} 향이 살아 있고 {taste} 맛인 음식",
        "점심으로 {ingredient} {method} 요리를 먹고 싶어",
        "저녁에 어울리는 {taste} {broth} 요리 추천해줘",
    )
    for index in range(800):
        family = families[index % len(families)]
        attrs = family["attributes"]
        text = (
            sensory_templates[index % len(sensory_templates)].format(**attrs)
            + f", 향은 {attrs['aroma']}이고 식감은 {attrs['texture']} 쪽으로"
        )
        queries.append({
            "id": f"query-{len(queries):04d}", "type": "sensory_without_menu", "text": text,
            "filters": {}, "sort": "RECOMMENDED",
            "gold": _gold({family["id"]}, menus, store_by_id),
            "expectedConcepts": [attrs["ingredient"], attrs["taste"], attrs["method"], family["baseName"]],
        })
    for index in range(400):
        menu = eligible_menus[(index * 23) % len(eligible_menus)]
        store = store_by_id[menu["storeId"]]
        family = families[int(menu["familyId"].split("-")[1])]
        max_price = menu["price"] + (index % 3) * 500
        filters = {"region": store["region"], "ambience": store["ambience"], "maxPrice": max_price}
        text = f"{store['region']}에서 {max_price}원 이하 {store['ambience']} 분위기의 {family['canonical']} 찾아줘"
        queries.append({
            "id": f"query-{len(queries):04d}", "type": "composite_filter", "text": text,
            "filters": filters, "sort": "RECOMMENDED",
            "gold": _gold({menu["familyId"]}, menus, store_by_id, filters),
            "expectedConcepts": [family["canonical"], *family["aliases"]],
        })
    match_modes = ("exact", "forward", "reverse", "alias")
    for index in range(300):
        family = families[index]
        mode = match_modes[index % len(match_modes)]
        if mode == "exact":
            phrase = family["canonical"]
        elif mode == "forward":
            phrase = family["variants"][2]
        elif mode == "reverse":
            phrase = f"아주 매력적인 {family['variants'][1]}"
        else:
            phrase = (
                f"{family['aliases'][0]} 중 {family['attributes']['aroma']}에 "
                f"{family['attributes']['texture']} 식감인 것"
            )
        text = f"{phrase} 파는 곳을 메뉴명 기준으로 찾아줘"
        gold = _gold({family["id"]}, menus, store_by_id)
        gold["matchMode"] = mode
        queries.append({
            "id": f"query-{len(queries):04d}", "type": "alias_bidirectional", "text": text,
            "filters": {}, "sort": "RECOMMENDED", "gold": gold,
            "expectedConcepts": [family["canonical"], *family["aliases"], *family["variants"]],
        })
    for index in range(200):
        family = families[index]
        sort = ("RECOMMENDED", "DISTANCE", "RATING")[index % 3]
        text = f"{family['canonical']} 파는 매장 중 {('추천순' if sort == 'RECOMMENDED' else '가까운순' if sort == 'DISTANCE' else '평점순')}으로 보여줘"
        gold = _gold({family["id"]}, menus, store_by_id)
        if sort == "DISTANCE":
            gold["storeIds"].sort(key=lambda value: store_by_id[value]["distanceMeters"])
        elif sort == "RATING":
            gold["storeIds"].sort(key=lambda value: (-store_by_id[value]["rating"], value))
        queries.append({
            "id": f"query-{len(queries):04d}", "type": "same_menu_ranking", "text": text,
            "filters": {}, "sort": sort, "gold": gold,
            "expectedConcepts": [family["canonical"], *family["aliases"]],
        })
    negative_adjectives = ("파란", "투명한", "무중력", "소리 없는", "네모난", "어제의", "꿈속", "차가운 불", "달빛", "구름맛", "숫자 없는", "역방향", "빈 접시", "존재하지 않는", "모호한")
    ambiguous_specs = (
        ("돌가루 수프", "국물", lambda family: family["attributes"]["broth"] == "국물"),
        ("전파 국수", "국수", lambda family: family["attributes"]["category"] == "NOODLE"),
        ("별빛 밥", "밥", lambda family: family["attributes"]["category"] == "RICE"),
        ("시간 튀김", "튀김", lambda family: family["attributes"]["method"] == "튀김"),
        ("공기 탕", "탕", lambda family: family["baseName"].endswith("탕")),
    )
    no_answer_nouns = ("그림자 도형", "무지개 파동", "침묵 좌표", "허공 기호", "가상 색채")
    for adjective in negative_adjectives:
        for noun, expected, predicate in ambiguous_specs:
            text = f"음 그 {adjective} {noun} 같은 거 있나 찾아 줘"
            family_ids = {family["id"] for family in families if predicate(family)}
            queries.append({
                "id": f"query-{len(queries):04d}", "type": "negative_or_ambiguous_voice", "text": text,
                "subtype": "ambiguous_food",
                "filters": {}, "sort": "RECOMMENDED",
                "gold": _gold(family_ids, menus, store_by_id),
                "expectedConcepts": [expected],
            })
        for noun in no_answer_nouns:
            text = f"음 그 {adjective} {noun} 같은 거 있나 찾아 줘"
            queries.append({
                "id": f"query-{len(queries):04d}", "type": "negative_or_ambiguous_voice", "text": text,
                "subtype": "true_no_answer",
                "filters": {}, "sort": "RECOMMENDED",
                "gold": {"familyIds": [], "menuIds": [], "storeIds": [], "negative": True, "provenance": [{"source": "deterministic-true-no-answer-template"}], "forbiddenMenuIds": [], "forbiddenStoreIds": []},
                "expectedConcepts": [],
            })
    for index in range(150):
        invalid = invalid_menus[index]
        store = store_by_id[invalid["storeId"]]
        family = families[int(invalid["familyId"].split("-")[1])]
        filters = {"region": store["region"]}
        gold = _gold({invalid["familyId"]}, menus, store_by_id, filters)
        gold["forbiddenMenuIds"] = [invalid["id"]]
        gold["forbiddenStoreIds"] = [store["id"]] if store["operationStatus"] == "CLOSED" or store["verificationStatus"] != "APPROVED" else []
        reason = "폐점 매장" if store["operationStatus"] == "CLOSED" else "비공개 또는 과거 버전"
        text = f"{store['region']}의 {family['canonical']} 중 {reason}은 빼고 현재 공개된 곳만 찾아줘"
        queries.append({
            "id": f"query-{len(queries):04d}", "type": "filter_defense", "text": text,
            "filters": filters, "sort": "RECOMMENDED", "gold": gold,
            "expectedConcepts": [family["canonical"], *family["aliases"]],
        })
    family_by_id = {family["id"]: family for family in families}
    core_attribute_keys = ("ingredient", "taste", "method", "broth")
    for query in queries:
        if query["type"] != "sensory_without_menu":
            acceptable = deepcopy(query["gold"])
            acceptable["policy"] = "strict-gold-equivalent-v1"
            query["acceptableGold"] = acceptable
            continue
        strict_family_id = query["gold"]["familyIds"][0]
        strict_attributes = family_by_id[strict_family_id]["attributes"]
        core_values = tuple(strict_attributes[key] for key in core_attribute_keys)
        acceptable_family_ids = {
            family["id"] for family in families
            if tuple(family["attributes"][key] for key in core_attribute_keys) == core_values
        }
        acceptable = _gold(acceptable_family_ids, menus, store_by_id, query["filters"])
        acceptable["policy"] = "sensory-core-attributes-v1"
        acceptable["provenance"] = [{
            "source": "deterministic-core-attribute-equivalence",
            "strictFamilyId": strict_family_id,
            "attributes": {
                key: strict_attributes[key] for key in core_attribute_keys
            },
        }]
        query["acceptableGold"] = acceptable
    if len({query["text"] for query in queries}) != len(queries):
        counts: dict[str, int] = {}
        for query in queries:
            counts[query["text"]] = counts.get(query["text"], 0) + 1
        duplicates = [(text, count) for text, count in counts.items() if count > 1]
        raise AssertionError(f"query templates produced duplicates: {duplicates[:10]}")
    return queries


def generate_dataset(config: DatasetConfig) -> dict[str, Any]:
    rng = random.Random(config.seed)
    families = _family_rows()
    stores = _stores(rng)
    menus = _menus(families, stores)
    queries = _queries(families, stores, menus)
    metadata = {
        "schemaVersion": config.schema_version,
        "seed": config.seed,
        "generator": "miriyum_search_eval.catalog",
    }
    fingerprint_source = json.dumps(
        {"metadata": metadata, "families": families, "stores": stores, "menus": menus, "queries": queries},
        ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    ).encode("utf-8")
    metadata["datasetSha256"] = sha256(fingerprint_source).hexdigest()
    return {"metadata": metadata, "families": families, "stores": stores, "menus": menus, "queries": queries}
