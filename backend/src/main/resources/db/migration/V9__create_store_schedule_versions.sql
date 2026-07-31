CREATE TABLE store_operating_schedule_versions (
    operating_schedule_version_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    version_number BIGINT NOT NULL,
    published_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (operating_schedule_version_id),
    CONSTRAINT uk_operating_schedule_store_version
        UNIQUE (store_id, version_number),
    CONSTRAINT fk_operating_schedule_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_operating_schedule_version
        CHECK (version_number >= 1),
    INDEX idx_operating_schedule_store (store_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE store_operating_schedule_entries (
    operating_schedule_version_id BIGINT NOT NULL,
    entry_order INT NOT NULL,
    day_of_week VARCHAR(10) NOT NULL,
    interval_kind VARCHAR(20) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    overnight BOOLEAN NOT NULL,
    week_start_minute INT NOT NULL,
    week_end_minute INT NOT NULL,
    PRIMARY KEY (operating_schedule_version_id, entry_order),
    CONSTRAINT fk_operating_schedule_entry_version
        FOREIGN KEY (operating_schedule_version_id)
        REFERENCES store_operating_schedule_versions (operating_schedule_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_operating_schedule_entry_day
        CHECK (day_of_week IN (
            'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
            'FRIDAY', 'SATURDAY', 'SUNDAY'
        )),
    CONSTRAINT ck_operating_schedule_entry_kind
        CHECK (interval_kind IN ('BUSINESS_HOURS', 'BREAK_TIME')),
    CONSTRAINT ck_operating_schedule_entry_nonzero
        CHECK (start_time <> end_time),
    CONSTRAINT ck_operating_schedule_entry_start
        CHECK (week_start_minute >= 0),
    CONSTRAINT ck_operating_schedule_entry_end
        CHECK (week_end_minute > week_start_minute)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE store_reservation_schedule_versions (
    reservation_schedule_version_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    version_number BIGINT NOT NULL,
    validated_operating_version_id BIGINT NOT NULL,
    published_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reservation_schedule_version_id),
    CONSTRAINT uk_reservation_schedule_store_version
        UNIQUE (store_id, version_number),
    CONSTRAINT fk_reservation_schedule_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_schedule_operating_version
        FOREIGN KEY (validated_operating_version_id)
        REFERENCES store_operating_schedule_versions (operating_schedule_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_schedule_version
        CHECK (version_number >= 1),
    INDEX idx_reservation_schedule_store (store_id),
    INDEX idx_reservation_schedule_operating_version (validated_operating_version_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE store_reservation_schedule_entries (
    reservation_schedule_version_id BIGINT NOT NULL,
    entry_order INT NOT NULL,
    day_of_week VARCHAR(10) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    overnight BOOLEAN NOT NULL,
    week_start_minute INT NOT NULL,
    week_end_minute INT NOT NULL,
    PRIMARY KEY (reservation_schedule_version_id, entry_order),
    CONSTRAINT fk_reservation_schedule_entry_version
        FOREIGN KEY (reservation_schedule_version_id)
        REFERENCES store_reservation_schedule_versions (reservation_schedule_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_schedule_entry_day
        CHECK (day_of_week IN (
            'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
            'FRIDAY', 'SATURDAY', 'SUNDAY'
        )),
    CONSTRAINT ck_reservation_schedule_entry_nonzero
        CHECK (start_time <> end_time),
    CONSTRAINT ck_reservation_schedule_entry_start
        CHECK (week_start_minute >= 0),
    CONSTRAINT ck_reservation_schedule_entry_end
        CHECK (week_end_minute > week_start_minute)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE store_schedule_state (
    store_id BIGINT NOT NULL,
    active_operating_schedule_version_id BIGINT NULL,
    active_reservation_schedule_version_id BIGINT NULL,
    next_operating_version BIGINT NOT NULL DEFAULT 1,
    next_reservation_version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (store_id),
    CONSTRAINT fk_store_schedule_state_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_store_schedule_state_active_operating
        FOREIGN KEY (active_operating_schedule_version_id)
        REFERENCES store_operating_schedule_versions (operating_schedule_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_store_schedule_state_active_reservation
        FOREIGN KEY (active_reservation_schedule_version_id)
        REFERENCES store_reservation_schedule_versions (reservation_schedule_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_store_schedule_next_operating
        CHECK (next_operating_version >= 1),
    CONSTRAINT ck_store_schedule_next_reservation
        CHECK (next_reservation_version >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
