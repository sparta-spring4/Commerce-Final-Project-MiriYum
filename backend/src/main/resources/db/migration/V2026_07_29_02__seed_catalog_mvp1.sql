-- 1차 MVP catalog seed (#31, v1) — 매장 도메인 소유자 승인 2026-07-29
-- 정책 원천: docs/service-policies/03-store-operation.md 초기 고정 후보의 최소 부분집합.
-- 적용 후 이 파일은 수정하지 않는다. 코드 추가·비활성은 새 migration으로 수행한다.

-- 매장 카테고리 (8)
INSERT INTO catalog_item (kind, code, display_name, active, sort_order) VALUES
    ('STORE_CATEGORY', 'KOREAN',      '한식',           TRUE, 1),
    ('STORE_CATEGORY', 'CHINESE',     '중식',           TRUE, 2),
    ('STORE_CATEGORY', 'JAPANESE',    '일식',           TRUE, 3),
    ('STORE_CATEGORY', 'WESTERN',     '양식',           TRUE, 4),
    ('STORE_CATEGORY', 'ASIAN',       '아시아 음식',    TRUE, 5),
    ('STORE_CATEGORY', 'CAFE_BAKERY', '카페·베이커리',  TRUE, 6),
    ('STORE_CATEGORY', 'BAR',         '주점',           TRUE, 7),
    ('STORE_CATEGORY', 'ETC',         '기타',           TRUE, 8);

-- 메뉴 카테고리 (10)
INSERT INTO catalog_item (kind, code, display_name, active, sort_order) VALUES
    ('MENU_CATEGORY', 'RICE',                  '밥요리',              TRUE, 1),
    ('MENU_CATEGORY', 'NOODLE',                '면요리',              TRUE, 2),
    ('MENU_CATEGORY', 'SOUP_STEW',             '국·탕·찌개',          TRUE, 3),
    ('MENU_CATEGORY', 'MEAT',                  '고기요리',            TRUE, 4),
    ('MENU_CATEGORY', 'SEAFOOD',               '해산물요리',          TRUE, 5),
    ('MENU_CATEGORY', 'PIZZA_BURGER_SANDWICH', '피자·버거·샌드위치',  TRUE, 6),
    ('MENU_CATEGORY', 'BAKERY',                '베이커리',            TRUE, 7),
    ('MENU_CATEGORY', 'DESSERT',               '디저트',              TRUE, 8),
    ('MENU_CATEGORY', 'BEVERAGE',              '음료',                TRUE, 9),
    ('MENU_CATEGORY', 'ETC',                   '기타',                TRUE, 10);

-- 매장 태그 (8)
INSERT INTO catalog_item (kind, code, display_name, active, sort_order) VALUES
    ('STORE_TAG', 'DATE',         '데이트',            TRUE, 1),
    ('STORE_TAG', 'QUIET',        '조용한',            TRUE, 2),
    ('STORE_TAG', 'GROUP',        '모임',              TRUE, 3),
    ('STORE_TAG', 'SOLO',         '혼밥',              TRUE, 4),
    ('STORE_TAG', 'FAMILY',       '가족식사',          TRUE, 5),
    ('STORE_TAG', 'VEGAN_OPTION', '비건 옵션',         TRUE, 6),
    ('STORE_TAG', 'ALLERGY_INFO', '알레르기 안내 제공', TRUE, 7),
    ('STORE_TAG', 'PET_FRIENDLY', '반려동물 가능',      TRUE, 8);

-- catalog 버전 (세 종류 모두 v1)
INSERT INTO catalog_version (catalog_kind, version) VALUES
    ('STORE_CATEGORY', 1),
    ('MENU_CATEGORY', 1),
    ('STORE_TAG', 1);
