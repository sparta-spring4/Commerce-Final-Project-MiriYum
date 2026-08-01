CREATE TABLE menus (
    menu_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    next_version_number INT NOT NULL,
    draft_version_number INT NULL,
    scheduled_version_number INT NULL,
    published_version_number INT NULL,
    visibility VARCHAR(20) NOT NULL,
    selling_status VARCHAR(20) NOT NULL,
    retired BOOLEAN NOT NULL,
    lock_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (menu_id),
    CONSTRAINT fk_menus_store
        FOREIGN KEY (store_id) REFERENCES stores (store_id) ON DELETE RESTRICT,
    CONSTRAINT ck_menus_next_version CHECK (next_version_number >= 2),
    CONSTRAINT ck_menus_visibility CHECK (visibility IN ('VISIBLE', 'HIDDEN')),
    CONSTRAINT ck_menus_selling_status
        CHECK (selling_status IN ('SELLING', 'SOLD_OUT', 'PAUSED')),
    CONSTRAINT ck_menus_distinct_pointers CHECK (
        (draft_version_number IS NULL OR scheduled_version_number IS NULL
            OR draft_version_number <> scheduled_version_number)
        AND (draft_version_number IS NULL OR published_version_number IS NULL
            OR draft_version_number <> published_version_number)
        AND (scheduled_version_number IS NULL OR published_version_number IS NULL
            OR scheduled_version_number <> published_version_number)
    ),
    CONSTRAINT ck_menus_retired_state CHECK (
        retired = FALSE
        OR (
            draft_version_number IS NULL
            AND scheduled_version_number IS NULL
            AND published_version_number IS NULL
            AND visibility = 'HIDDEN'
            AND selling_status = 'PAUSED'
        )
    ),
    INDEX idx_menus_store (store_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE menu_versions (
    menu_version_id BIGINT NOT NULL AUTO_INCREMENT,
    menu_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    price INT NOT NULL,
    representative BOOLEAN NOT NULL,
    primary_category_code VARCHAR(50) COLLATE utf8mb4_0900_as_cs NOT NULL,
    hold_selection_allowed BOOLEAN NOT NULL,
    pickup_selection_allowed BOOLEAN NOT NULL,
    allergen_information_status VARCHAR(20) NOT NULL,
    origin_information_status VARCHAR(20) NOT NULL,
    alcoholic BOOLEAN NOT NULL,
    created_by_operator_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    effective_at DATETIME(6) NULL,
    PRIMARY KEY (menu_version_id),
    CONSTRAINT uk_menu_versions_number UNIQUE (menu_id, version_number),
    CONSTRAINT fk_menu_versions_menu
        FOREIGN KEY (menu_id) REFERENCES menus (menu_id) ON DELETE RESTRICT,
    CONSTRAINT fk_menu_versions_primary_category
        FOREIGN KEY (primary_category_code)
        REFERENCES menu_category (code) ON DELETE RESTRICT,
    CONSTRAINT fk_menu_versions_creator
        FOREIGN KEY (created_by_operator_id)
        REFERENCES store_operator_accounts (store_operator_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_menu_versions_number CHECK (version_number >= 1),
    CONSTRAINT ck_menu_versions_status
        CHECK (status IN ('DRAFT', 'SCHEDULED', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_menu_versions_price CHECK (price >= 0),
    CONSTRAINT ck_menu_versions_allergen_status CHECK (
        allergen_information_status IN ('REGISTERED', 'NOT_REGISTERED')
    ),
    CONSTRAINT ck_menu_versions_origin_status CHECK (
        origin_information_status IN ('REGISTERED', 'NOT_REGISTERED', 'NOT_APPLICABLE')
    ),
    CONSTRAINT ck_menu_versions_effective_time CHECK (
        (status = 'DRAFT' AND effective_at IS NULL)
        OR (status IN ('SCHEDULED', 'PUBLISHED') AND effective_at IS NOT NULL)
        OR status = 'RETIRED'
    ),
    INDEX idx_menu_versions_schedule (status, effective_at, menu_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE menu_version_secondary_categories (
    menu_version_id BIGINT NOT NULL,
    sort_order INT NOT NULL,
    category_code VARCHAR(50) COLLATE utf8mb4_0900_as_cs NOT NULL,
    PRIMARY KEY (menu_version_id, sort_order),
    CONSTRAINT uk_menu_version_secondary_category
        UNIQUE (menu_version_id, category_code),
    CONSTRAINT fk_menu_version_secondary_version
        FOREIGN KEY (menu_version_id)
        REFERENCES menu_versions (menu_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_menu_version_secondary_category
        FOREIGN KEY (category_code)
        REFERENCES menu_category (code) ON DELETE RESTRICT,
    CONSTRAINT ck_menu_version_secondary_order CHECK (sort_order BETWEEN 0 AND 4)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE menu_version_local_tags (
    menu_version_id BIGINT NOT NULL,
    sort_order INT NOT NULL,
    tag_value VARCHAR(30) COLLATE utf8mb4_0900_as_cs NOT NULL,
    PRIMARY KEY (menu_version_id, sort_order),
    CONSTRAINT uk_menu_version_local_tag UNIQUE (menu_version_id, tag_value),
    CONSTRAINT fk_menu_version_local_tag_version
        FOREIGN KEY (menu_version_id)
        REFERENCES menu_versions (menu_version_id) ON DELETE RESTRICT,
    CONSTRAINT ck_menu_version_local_tag_order CHECK (sort_order BETWEEN 0 AND 9)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE menu_version_allergen_disclosures (
    menu_version_id BIGINT NOT NULL,
    sort_order INT NOT NULL,
    ingredient_name VARCHAR(100) NOT NULL,
    disclosure_status VARCHAR(20) NOT NULL,
    PRIMARY KEY (menu_version_id, sort_order),
    CONSTRAINT fk_menu_version_allergen_version
        FOREIGN KEY (menu_version_id)
        REFERENCES menu_versions (menu_version_id) ON DELETE RESTRICT,
    CONSTRAINT ck_menu_version_allergen_order CHECK (sort_order BETWEEN 0 AND 19),
    CONSTRAINT ck_menu_version_allergen_status
        CHECK (disclosure_status IN ('CONTAINS', 'MAY_CONTAIN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE menu_version_origin_disclosures (
    menu_version_id BIGINT NOT NULL,
    sort_order INT NOT NULL,
    ingredient_name VARCHAR(100) NOT NULL,
    origin_label VARCHAR(200) NOT NULL,
    PRIMARY KEY (menu_version_id, sort_order),
    CONSTRAINT fk_menu_version_origin_version
        FOREIGN KEY (menu_version_id)
        REFERENCES menu_versions (menu_version_id) ON DELETE RESTRICT,
    CONSTRAINT ck_menu_version_origin_order CHECK (sort_order BETWEEN 0 AND 19)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE menu_publication_events (
    menu_publication_event_id BIGINT NOT NULL AUTO_INCREMENT,
    menu_id BIGINT NOT NULL,
    version_number INT NULL,
    event_type VARCHAR(40) NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_operator_id BIGINT NULL,
    commanded_at DATETIME(6) NOT NULL,
    effective_at DATETIME(6) NULL,
    confirmed_at DATETIME(6) NULL,
    request_id VARCHAR(100) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    previous_version_number INT NULL,
    new_version_number INT NULL,
    changed_fields VARCHAR(1000) NOT NULL,
    change_reason VARCHAR(500) NULL,
    previous_visibility VARCHAR(20) NULL,
    new_visibility VARCHAR(20) NULL,
    previous_selling_status VARCHAR(20) NULL,
    new_selling_status VARCHAR(20) NULL,
    impact_check_status VARCHAR(20) NOT NULL,
    impact_count INT NULL,
    recovery_result VARCHAR(20) NOT NULL,
    PRIMARY KEY (menu_publication_event_id),
    CONSTRAINT fk_menu_publication_event_menu
        FOREIGN KEY (menu_id) REFERENCES menus (menu_id) ON DELETE RESTRICT,
    CONSTRAINT fk_menu_publication_event_version
        FOREIGN KEY (menu_id, version_number)
        REFERENCES menu_versions (menu_id, version_number) ON DELETE RESTRICT,
    CONSTRAINT fk_menu_publication_event_actor
        FOREIGN KEY (actor_operator_id)
        REFERENCES store_operator_accounts (store_operator_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_menu_publication_event_type CHECK (
        event_type IN (
            'DRAFT_CREATED',
            'DRAFT_UPDATED',
            'PUBLISHED_IMMEDIATELY',
            'PUBLICATION_SCHEDULED',
            'SCHEDULE_CANCELLED',
            'SCHEDULE_ACTIVATED',
            'VISIBILITY_CHANGED',
            'SELLING_STATUS_CHANGED',
            'RETIRED'
        )
    ),
    CONSTRAINT ck_menu_audit_actor CHECK (actor_type IN ('OPERATOR', 'SYSTEM')),
    CONSTRAINT ck_menu_audit_outcome CHECK (outcome = 'SUCCEEDED'),
    CONSTRAINT ck_menu_audit_impact CHECK (
        (impact_check_status = 'NOT_APPLICABLE' AND impact_count IS NULL)
        OR (impact_check_status = 'NOT_EVALUATED' AND impact_count IS NULL)
    ),
    CONSTRAINT ck_menu_audit_recovery
        CHECK (recovery_result IN ('NOT_APPLICABLE', 'NOT_EVALUATED')),
    INDEX idx_menu_publication_events_menu (menu_id, menu_publication_event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
