-- 매장·메뉴 catalog 기반 스키마 (#31)
-- 종류별 테이블 분리(코드 리뷰 반영): code를 자연 PK로 두어 후속 Store/Menu가 code 단독 FK를 걸 수 있게 한다.
-- code 컬럼은 대소문자 구분 collation(utf8mb4_0900_as_cs)으로 두어 애플리케이션 검증과 DB 비교를 일치시킨다.
-- code 형식은 OpenAPI 정본 패턴 ^[A-Z][A-Z0-9_]{1,49}$ (최소 2자, 대문자 시작)를 CHECK REGEXP_LIKE로 강제한다.
-- 적용 후 이 파일은 수정하지 않는다. 변경은 새 migration으로 수행한다.

CREATE TABLE store_category (
    code         VARCHAR(50)  COLLATE utf8mb4_0900_as_cs NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    active       BOOLEAN      NOT NULL,
    sort_order   INT          NOT NULL,
    PRIMARY KEY (code),
    CONSTRAINT ck_store_category_sort_order CHECK (sort_order > 0),
    CONSTRAINT ck_store_category_code_format
        CHECK (REGEXP_LIKE(code, '^[A-Z][A-Z0-9_]{1,49}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE menu_category (
    code         VARCHAR(50)  COLLATE utf8mb4_0900_as_cs NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    active       BOOLEAN      NOT NULL,
    sort_order   INT          NOT NULL,
    PRIMARY KEY (code),
    CONSTRAINT ck_menu_category_sort_order CHECK (sort_order > 0),
    CONSTRAINT ck_menu_category_code_format
        CHECK (REGEXP_LIKE(code, '^[A-Z][A-Z0-9_]{1,49}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE store_tag (
    code         VARCHAR(50)  COLLATE utf8mb4_0900_as_cs NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    active       BOOLEAN      NOT NULL,
    sort_order   INT          NOT NULL,
    PRIMARY KEY (code),
    CONSTRAINT ck_store_tag_sort_order CHECK (sort_order > 0),
    CONSTRAINT ck_store_tag_code_format
        CHECK (REGEXP_LIKE(code, '^[A-Z][A-Z0-9_]{1,49}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 런타임 버전 테이블(catalog_version)은 현재 MVP에 소비 코드가 없어 두지 않는다(YAGNI).
-- 승인된 seed 버전은 v1이며 seed 마이그레이션 주석과 승인 문서에만 기록한다.
-- 운영자 CRUD·버전 증가·감사·캐시 무효화는 고도화 범위다.
