-- 매장·메뉴 catalog 기반 스키마 (#31)
-- 종류별 테이블 분리(코드 리뷰 반영): code를 자연 PK로 두어 후속 Store/Menu가 code 단독 FK를 걸 수 있게 한다.
-- code 컬럼은 대소문자 구분 collation(utf8mb4_0900_as_cs)으로 두어 애플리케이션 검증과 DB 비교를 일치시킨다.
-- 적용 후 이 파일은 수정하지 않는다. 변경은 새 migration으로 수행한다.

CREATE TABLE store_category (
    code         VARCHAR(50)  COLLATE utf8mb4_0900_as_cs NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    active       BOOLEAN      NOT NULL,
    sort_order   INT          NOT NULL,
    PRIMARY KEY (code),
    CONSTRAINT ck_store_category_sort_order CHECK (sort_order > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE menu_category (
    code         VARCHAR(50)  COLLATE utf8mb4_0900_as_cs NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    active       BOOLEAN      NOT NULL,
    sort_order   INT          NOT NULL,
    PRIMARY KEY (code),
    CONSTRAINT ck_menu_category_sort_order CHECK (sort_order > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE store_tag (
    code         VARCHAR(50)  COLLATE utf8mb4_0900_as_cs NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    active       BOOLEAN      NOT NULL,
    sort_order   INT          NOT NULL,
    PRIMARY KEY (code),
    CONSTRAINT ck_store_tag_sort_order CHECK (sort_order > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE catalog_version (
    catalog VARCHAR(30) NOT NULL,
    version BIGINT      NOT NULL,
    PRIMARY KEY (catalog),
    CONSTRAINT ck_catalog_version_positive CHECK (version > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
