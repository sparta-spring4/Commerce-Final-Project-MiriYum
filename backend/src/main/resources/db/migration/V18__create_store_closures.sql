CREATE TABLE store_regular_closure_versions (
    regular_closure_version_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    version_number BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    time_zone_id VARCHAR(64) NOT NULL,
    effective_at DATETIME(6) NULL,
    activated_at DATETIME(6) NULL,
    change_reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (regular_closure_version_id),
    CONSTRAINT uk_regular_closure_store_version
        UNIQUE (store_id, version_number),
    CONSTRAINT uk_regular_closure_store_id
        UNIQUE (store_id, regular_closure_version_id),
    CONSTRAINT fk_regular_closure_store
        FOREIGN KEY (store_id) REFERENCES stores (store_id) ON DELETE RESTRICT,
    CONSTRAINT ck_regular_closure_version CHECK (version_number >= 1),
    CONSTRAINT ck_regular_closure_status CHECK (status IN (
        'DRAFT', 'SCHEDULED', 'ACTIVE', 'RETIRED', 'ACTIVATION_FAILED'
    )),
    INDEX idx_regular_closure_global_due (status, effective_at, version_number)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE store_regular_closure_entries (
    regular_closure_version_id BIGINT NOT NULL,
    entry_order INT NOT NULL,
    rule_type VARCHAR(10) NOT NULL,
    day_of_week VARCHAR(10) NULL,
    closure_date DATE NULL,
    PRIMARY KEY (regular_closure_version_id, entry_order),
    CONSTRAINT fk_regular_closure_entry_version
        FOREIGN KEY (regular_closure_version_id)
        REFERENCES store_regular_closure_versions (regular_closure_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_regular_closure_entry_shape CHECK (
        (rule_type = 'WEEKLY' AND day_of_week IS NOT NULL AND closure_date IS NULL)
        OR (rule_type = 'DATE' AND day_of_week IS NULL AND closure_date IS NOT NULL)
    ),
    CONSTRAINT uk_regular_closure_entry_weekly
        UNIQUE (regular_closure_version_id, rule_type, day_of_week),
    CONSTRAINT uk_regular_closure_entry_date
        UNIQUE (regular_closure_version_id, rule_type, closure_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE store_schedule_state
    ADD COLUMN active_regular_closure_version_id BIGINT NULL,
    ADD COLUMN next_regular_closure_version BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT fk_schedule_state_regular_closure
        FOREIGN KEY (store_id, active_regular_closure_version_id)
        REFERENCES store_regular_closure_versions (
            store_id, regular_closure_version_id
        ) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_schedule_state_next_regular_closure
        CHECK (next_regular_closure_version >= 1);

CREATE TABLE store_temporary_closures (
    temporary_closure_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    start_at DATETIME(6) NOT NULL,
    end_at DATETIME(6) NOT NULL,
    time_zone_id VARCHAR(64) NOT NULL,
    reason VARCHAR(30) NOT NULL,
    public_message VARCHAR(200) NULL,
    cancelled_at DATETIME(6) NULL,
    change_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (temporary_closure_id),
    CONSTRAINT fk_temporary_closure_store
        FOREIGN KEY (store_id) REFERENCES stores (store_id) ON DELETE RESTRICT,
    CONSTRAINT ck_temporary_closure_interval CHECK (start_at < end_at),
    CONSTRAINT ck_temporary_closure_version CHECK (change_version >= 1),
    CONSTRAINT ck_temporary_closure_reason CHECK (reason IN (
        'MAINTENANCE', 'STAFFING', 'PRIVATE_EVENT', 'OTHER'
    )),
    INDEX idx_temporary_closure_overlap (store_id, cancelled_at, start_at, end_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE store_closure_audit_events (
    closure_audit_event_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    actor_id BIGINT NULL,
    resource_type VARCHAR(30) NOT NULL,
    resource_id VARCHAR(100) NOT NULL,
    action VARCHAR(50) NOT NULL,
    previous_state VARCHAR(30) NULL,
    new_state VARCHAR(30) NOT NULL,
    time_zone_id VARCHAR(64) NOT NULL,
    effective_at DATETIME(6) NULL,
    occurred_at DATETIME(6) NOT NULL,
    change_reason VARCHAR(500) NULL,
    request_id VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (closure_audit_event_id),
    CONSTRAINT fk_closure_audit_store
        FOREIGN KEY (store_id) REFERENCES stores (store_id) ON DELETE RESTRICT,
    INDEX idx_closure_audit_store_occurred (store_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
