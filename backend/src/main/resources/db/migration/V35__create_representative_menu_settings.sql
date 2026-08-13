CREATE TABLE representative_menu_settings (
    store_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    lock_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (store_id),
    CONSTRAINT fk_representative_menu_setting_store
        FOREIGN KEY (store_id) REFERENCES stores (store_id) ON DELETE RESTRICT,
    CONSTRAINT ck_representative_menu_setting_version CHECK (version >= 0),
    CONSTRAINT ck_representative_menu_setting_status
        CHECK (status IN ('UNCONFIGURED', 'CONFIGURED', 'REQUIRES_ATTENTION'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE representative_menu_entries (
    store_id BIGINT NOT NULL,
    display_order INT NOT NULL,
    menu_id BIGINT NOT NULL,
    PRIMARY KEY (store_id, display_order),
    CONSTRAINT uk_representative_menu_entry_menu UNIQUE (store_id, menu_id),
    CONSTRAINT fk_representative_menu_entry_setting
        FOREIGN KEY (store_id) REFERENCES representative_menu_settings (store_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_representative_menu_entry_menu
        FOREIGN KEY (menu_id) REFERENCES menus (menu_id) ON DELETE RESTRICT,
    CONSTRAINT ck_representative_menu_entry_order CHECK (display_order BETWEEN 1 AND 5)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE representative_menu_audits (
    representative_menu_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    before_version BIGINT NOT NULL,
    after_version BIGINT NOT NULL,
    before_status VARCHAR(30) NOT NULL,
    after_status VARCHAR(30) NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_operator_id BIGINT NULL,
    event_type VARCHAR(30) NOT NULL,
    trigger_menu_id BIGINT NULL,
    ordered_menu_ids_json JSON NOT NULL,
    request_id VARCHAR(100) COLLATE utf8mb4_0900_as_cs NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (representative_menu_audit_id),
    CONSTRAINT fk_representative_menu_audit_store
        FOREIGN KEY (store_id) REFERENCES stores (store_id) ON DELETE RESTRICT,
    CONSTRAINT fk_representative_menu_audit_actor
        FOREIGN KEY (actor_operator_id)
        REFERENCES store_operator_accounts (store_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT fk_representative_menu_audit_trigger_menu
        FOREIGN KEY (trigger_menu_id) REFERENCES menus (menu_id) ON DELETE RESTRICT,
    CONSTRAINT ck_representative_menu_audit_versions
        CHECK (before_version >= 0 AND after_version = before_version + 1),
    CONSTRAINT ck_representative_menu_audit_before_status
        CHECK (before_status IN ('UNCONFIGURED', 'CONFIGURED', 'REQUIRES_ATTENTION')),
    CONSTRAINT ck_representative_menu_audit_after_status
        CHECK (after_status IN ('CONFIGURED', 'REQUIRES_ATTENTION')),
    CONSTRAINT ck_representative_menu_audit_actor_type
        CHECK (actor_type IN ('OPERATOR', 'SYSTEM')),
    CONSTRAINT ck_representative_menu_audit_event_type
        CHECK (event_type IN ('REPLACED', 'AUTO_REMOVED')),
    CONSTRAINT ck_representative_menu_audit_actor_shape CHECK (
        (actor_type = 'OPERATOR' AND actor_operator_id IS NOT NULL)
        OR (actor_type = 'SYSTEM' AND actor_operator_id IS NULL)
    ),
    CONSTRAINT ck_representative_menu_audit_trigger_shape CHECK (
        (event_type = 'REPLACED' AND trigger_menu_id IS NULL)
        OR (event_type = 'AUTO_REMOVED' AND trigger_menu_id IS NOT NULL)
    ),
    INDEX idx_representative_menu_audit_store
        (store_id, representative_menu_audit_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
