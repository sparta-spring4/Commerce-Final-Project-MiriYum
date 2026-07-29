-- 매장·메뉴 catalog 기반 스키마 (#31)
-- 적용 후 이 파일은 수정하지 않는다. 변경은 새 migration으로 수행한다.

CREATE TABLE catalog_item (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    kind         VARCHAR(20)  NOT NULL,
    code         VARCHAR(50)  NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    active       BOOLEAN      NOT NULL,
    sort_order   INT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_catalog_item_kind_code UNIQUE (kind, code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_catalog_item_kind_active_sort ON catalog_item (kind, active, sort_order);

CREATE TABLE catalog_version (
    catalog_kind VARCHAR(20) NOT NULL,
    version      BIGINT      NOT NULL,
    PRIMARY KEY (catalog_kind)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
