-- Issue #89: Reservation 소유의 매장별 시간 정책 버전 (Flyway V20).
CREATE TABLE reservation_time_policy_versions (
    reservation_time_policy_version_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    version_number BIGINT NOT NULL,
    slot_interval_minutes INT NOT NULL,
    service_duration_minutes INT NOT NULL,
    turnover_duration_minutes INT NOT NULL,
    status VARCHAR(30) NOT NULL,
    effective_at DATETIME(6) NULL,
    activated_at DATETIME(6) NULL,
    publication_requested_at DATETIME(6) NULL,
    change_reason VARCHAR(500) NULL,
    active_store_guard BIGINT GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' THEN store_id ELSE NULL END
    ) STORED,
    scheduled_store_guard BIGINT GENERATED ALWAYS AS (
        CASE WHEN status = 'SCHEDULED' THEN store_id ELSE NULL END
    ) STORED,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reservation_time_policy_version_id),
    CONSTRAINT uk_reservation_time_policy_store_version
        UNIQUE (store_id, version_number),
    CONSTRAINT uk_reservation_time_policy_active_store
        UNIQUE (active_store_guard),
    CONSTRAINT uk_reservation_time_policy_scheduled_store
        UNIQUE (scheduled_store_guard),
    CONSTRAINT fk_reservation_time_policy_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_time_policy_identity
        CHECK (version_number > 0),
    CONSTRAINT ck_reservation_time_policy_durations
        CHECK (
            slot_interval_minutes BETWEEN 1 AND 1440
            AND service_duration_minutes BETWEEN 1 AND 1440
            AND turnover_duration_minutes BETWEEN 0 AND 1440
            AND service_duration_minutes + turnover_duration_minutes <= 1440
        ),
    CONSTRAINT ck_reservation_time_policy_status
        CHECK (
            status IN (
                'DRAFT',
                'SCHEDULED',
                'ACTIVE',
                'RETIRED',
                'ACTIVATION_FAILED'
            )
        ),
    CONSTRAINT ck_reservation_time_policy_lifecycle
        CHECK (
            (
                status = 'DRAFT'
                AND effective_at IS NULL
                AND activated_at IS NULL
            )
            OR (
                status = 'SCHEDULED'
                AND effective_at IS NOT NULL
                AND activated_at IS NULL
                AND publication_requested_at IS NOT NULL
                AND change_reason IS NOT NULL
                AND CHAR_LENGTH(TRIM(change_reason)) BETWEEN 1 AND 500
            )
            OR (
                status IN ('ACTIVE', 'RETIRED')
                AND effective_at IS NOT NULL
                AND activated_at IS NOT NULL
                AND activated_at >= effective_at
                AND publication_requested_at IS NOT NULL
                AND change_reason IS NOT NULL
                AND CHAR_LENGTH(TRIM(change_reason)) BETWEEN 1 AND 500
            )
            OR (
                status = 'ACTIVATION_FAILED'
                AND effective_at IS NOT NULL
                AND activated_at IS NULL
                AND publication_requested_at IS NOT NULL
                AND change_reason IS NOT NULL
                AND CHAR_LENGTH(TRIM(change_reason)) BETWEEN 1 AND 500
            )
        ),
    INDEX idx_reservation_time_policy_effectivity (
        store_id,
        status,
        effective_at
    ),
    INDEX idx_reservation_time_policy_due_activation (
        status,
        effective_at,
        reservation_time_policy_version_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reservation_time_policy_audits (
    reservation_time_policy_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    actor_type VARCHAR(30) NOT NULL,
    actor_id BIGINT NULL,
    target_version BIGINT NOT NULL,
    previous_active_version BIGINT NULL,
    new_active_version BIGINT NULL,
    before_status VARCHAR(30) NULL,
    after_status VARCHAR(30) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    effective_at DATETIME(6) NULL,
    occurred_at DATETIME(6) NOT NULL,
    change_reason VARCHAR(500) NULL,
    outcome VARCHAR(30) NOT NULL,
    command_id VARCHAR(128) NOT NULL,
    conflict_check_status VARCHAR(30) NOT NULL,
    conflict_count BIGINT NULL,
    PRIMARY KEY (reservation_time_policy_audit_id),
    CONSTRAINT fk_reservation_time_policy_audit_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_time_policy_audit_actor
        FOREIGN KEY (actor_id)
        REFERENCES store_operator_accounts (store_operator_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_time_policy_audit_target
        FOREIGN KEY (store_id, target_version)
        REFERENCES reservation_time_policy_versions (store_id, version_number)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_time_policy_audit_actor
        CHECK (
            (actor_type = 'STORE_OPERATOR' AND actor_id IS NOT NULL)
            OR (actor_type = 'SYSTEM' AND actor_id IS NULL)
        ),
    CONSTRAINT ck_reservation_time_policy_audit_statuses
        CHECK (
            before_status IS NULL
            OR before_status IN (
                'DRAFT',
                'SCHEDULED',
                'ACTIVE',
                'RETIRED',
                'ACTIVATION_FAILED'
            )
        ),
    CONSTRAINT ck_reservation_time_policy_audit_after_status
        CHECK (
            after_status IN (
                'DRAFT',
                'SCHEDULED',
                'ACTIVE',
                'RETIRED',
                'ACTIVATION_FAILED'
            )
        ),
    CONSTRAINT ck_reservation_time_policy_audit_outcome
        CHECK (outcome IN ('SUCCEEDED', 'ACTIVATION_FAILED')),
    CONSTRAINT ck_reservation_time_policy_audit_conflict
        CHECK (
            (
                conflict_check_status = 'NOT_EVALUATED'
                AND conflict_count IS NULL
            )
            OR (
                conflict_check_status = 'EVALUATED'
                AND conflict_count IS NOT NULL
                AND conflict_count >= 0
            )
        ),
    CONSTRAINT ck_reservation_time_policy_audit_reason
        CHECK (
            change_reason IS NULL
            OR CHAR_LENGTH(TRIM(change_reason)) BETWEEN 1 AND 500
        ),
    INDEX idx_reservation_time_policy_audit_store_occurred (
        store_id,
        occurred_at,
        reservation_time_policy_audit_id
    ),
    INDEX idx_reservation_time_policy_audit_command (
        actor_type,
        command_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- V15의 현지 LocalTime 열은 기존 행 호환을 위해 남기되 신규 예약은 실제 Instant 스냅샷을 쓴다.
-- 과거 행의 offset을 추측해 소급 변환하지 않으며, 신규 판정에서는 Instant 스냅샷이 없는 행을
-- fail-closed로 취급한다.
ALTER TABLE reservations
    DROP CHECK ck_reservations_service_time,
    MODIFY COLUMN start_time TIME(6) NULL,
    MODIFY COLUMN end_time TIME(6) NULL,
    ADD COLUMN start_at DATETIME(6) NULL AFTER service_date,
    ADD COLUMN service_end_at DATETIME(6) NULL AFTER start_at,
    ADD COLUMN occupancy_end_at DATETIME(6) NULL AFTER service_end_at,
    ADD COLUMN time_zone_id_snapshot VARCHAR(64) NULL AFTER occupancy_end_at,
    ADD COLUMN start_offset_seconds INT NULL AFTER time_zone_id_snapshot,
    ADD COLUMN service_end_offset_seconds INT NULL AFTER start_offset_seconds,
    ADD COLUMN occupancy_end_offset_seconds INT NULL AFTER service_end_offset_seconds,
    ADD COLUMN slot_interval_minutes INT NULL AFTER occupancy_end_offset_seconds,
    ADD COLUMN service_duration_minutes INT NULL AFTER slot_interval_minutes,
    ADD COLUMN turnover_duration_minutes INT NULL AFTER service_duration_minutes,
    ADD COLUMN reservation_time_policy_store_id BIGINT NULL
        AFTER turnover_duration_minutes,
    ADD CONSTRAINT ck_reservations_time_snapshot
        CHECK (
            (
                start_at IS NULL
                AND service_end_at IS NULL
                AND occupancy_end_at IS NULL
                AND time_zone_id_snapshot IS NULL
                AND start_offset_seconds IS NULL
                AND service_end_offset_seconds IS NULL
                AND occupancy_end_offset_seconds IS NULL
                AND slot_interval_minutes IS NULL
                AND service_duration_minutes IS NULL
                AND turnover_duration_minutes IS NULL
                AND reservation_time_policy_store_id IS NULL
                AND start_time IS NOT NULL
                AND end_time IS NOT NULL
                AND start_time < end_time
            )
            OR (
                start_at IS NOT NULL
                AND service_end_at IS NOT NULL
                AND occupancy_end_at IS NOT NULL
                AND time_zone_id_snapshot IS NOT NULL
                AND CHAR_LENGTH(TRIM(time_zone_id_snapshot)) BETWEEN 1 AND 64
                AND start_offset_seconds IS NOT NULL
                AND service_end_offset_seconds IS NOT NULL
                AND occupancy_end_offset_seconds IS NOT NULL
                AND start_offset_seconds BETWEEN -64800 AND 64800
                AND service_end_offset_seconds BETWEEN -64800 AND 64800
                AND occupancy_end_offset_seconds BETWEEN -64800 AND 64800
                AND slot_interval_minutes IS NOT NULL
                AND service_duration_minutes IS NOT NULL
                AND turnover_duration_minutes IS NOT NULL
                AND slot_interval_minutes BETWEEN 1 AND 1440
                AND service_duration_minutes BETWEEN 1 AND 1440
                AND turnover_duration_minutes BETWEEN 0 AND 1440
                AND service_duration_minutes + turnover_duration_minutes <= 1440
                AND reservation_time_policy_store_id IS NOT NULL
                AND reservation_time_policy_store_id = store_id
                AND SECOND(start_at) = 0
                AND MICROSECOND(start_at) = 0
                AND start_at < service_end_at
                AND service_end_at <= occupancy_end_at
                AND service_end_at = TIMESTAMPADD(
                    MINUTE,
                    service_duration_minutes,
                    start_at
                )
                AND occupancy_end_at = TIMESTAMPADD(
                    MINUTE,
                    turnover_duration_minutes,
                    service_end_at
                )
                AND start_time IS NULL
                AND end_time IS NULL
            )
        ),
    ADD INDEX idx_reservations_store_occupancy (
        store_id,
        start_at,
        occupancy_end_at,
        reservation_id
    );
