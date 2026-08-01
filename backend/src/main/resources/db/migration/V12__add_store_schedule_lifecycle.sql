ALTER TABLE store_operating_schedule_versions
    CHANGE COLUMN published_at activated_at TIMESTAMP(6) NULL,
    ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE' AFTER version_number,
    ADD COLUMN time_zone_id VARCHAR(64) NULL AFTER status,
    ADD COLUMN effective_at TIMESTAMP(6) NULL AFTER time_zone_id,
    ADD COLUMN change_reason VARCHAR(500) NULL AFTER activated_at,
    ADD COLUMN conflict_check_status VARCHAR(20) NOT NULL
        DEFAULT 'NOT_EVALUATED' AFTER change_reason,
    ADD COLUMN conflict_count INT NULL AFTER conflict_check_status;

UPDATE store_operating_schedule_versions version
JOIN stores store_row ON store_row.store_id = version.store_id
SET version.time_zone_id = store_row.time_zone_id
WHERE version.time_zone_id IS NULL;

ALTER TABLE store_operating_schedule_versions
    MODIFY COLUMN time_zone_id VARCHAR(64) NOT NULL,
    ALTER COLUMN status DROP DEFAULT,
    ALTER COLUMN conflict_check_status DROP DEFAULT,
    ADD CONSTRAINT uk_operating_schedule_effective_at
        UNIQUE (store_id, effective_at),
    ADD CONSTRAINT ck_operating_schedule_status
        CHECK (status IN (
            'DRAFT', 'SCHEDULED', 'ACTIVE', 'RETIRED', 'ACTIVATION_FAILED'
        )),
    ADD CONSTRAINT ck_operating_schedule_conflict_check
        CHECK (conflict_check_status IN ('NOT_EVALUATED', 'EVALUATED')),
    ADD INDEX idx_operating_schedule_due
        (store_id, status, effective_at, version_number);

ALTER TABLE store_reservation_schedule_versions
    CHANGE COLUMN published_at activated_at TIMESTAMP(6) NULL,
    ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE' AFTER version_number,
    ADD COLUMN time_zone_id VARCHAR(64) NULL AFTER status,
    ADD COLUMN effective_at TIMESTAMP(6) NULL AFTER time_zone_id,
    ADD COLUMN change_reason VARCHAR(500) NULL AFTER activated_at,
    ADD COLUMN conflict_check_status VARCHAR(20) NOT NULL
        DEFAULT 'NOT_EVALUATED' AFTER change_reason,
    ADD COLUMN conflict_count INT NULL AFTER conflict_check_status;

UPDATE store_reservation_schedule_versions version
JOIN stores store_row ON store_row.store_id = version.store_id
SET version.time_zone_id = store_row.time_zone_id
WHERE version.time_zone_id IS NULL;

ALTER TABLE store_reservation_schedule_versions
    MODIFY COLUMN time_zone_id VARCHAR(64) NOT NULL,
    ALTER COLUMN status DROP DEFAULT,
    ALTER COLUMN conflict_check_status DROP DEFAULT,
    ADD CONSTRAINT uk_reservation_schedule_effective_at
        UNIQUE (store_id, effective_at),
    ADD CONSTRAINT ck_reservation_schedule_status
        CHECK (status IN (
            'DRAFT', 'SCHEDULED', 'ACTIVE', 'RETIRED', 'ACTIVATION_FAILED'
        )),
    ADD CONSTRAINT ck_reservation_schedule_conflict_check
        CHECK (conflict_check_status IN ('NOT_EVALUATED', 'EVALUATED')),
    ADD INDEX idx_reservation_schedule_due
        (store_id, status, effective_at, version_number);

CREATE TABLE store_schedule_audit_events (
    store_schedule_audit_event_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    schedule_stream VARCHAR(20) NOT NULL,
    target_version BIGINT NOT NULL,
    previous_active_version BIGINT NULL,
    new_active_version BIGINT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_id BIGINT NULL,
    action VARCHAR(50) NOT NULL,
    previous_status VARCHAR(30) NULL,
    new_status VARCHAR(30) NOT NULL,
    time_zone_id VARCHAR(64) NOT NULL,
    requested_at TIMESTAMP(6) NOT NULL,
    effective_at TIMESTAMP(6) NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    change_reason VARCHAR(500) NULL,
    request_id VARCHAR(64) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    conflict_check_status VARCHAR(20) NOT NULL,
    conflict_count INT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (store_schedule_audit_event_id),
    CONSTRAINT fk_schedule_audit_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_schedule_audit_stream
        CHECK (schedule_stream IN ('OPERATING', 'RESERVATION')),
    CONSTRAINT ck_schedule_audit_actor
        CHECK (actor_type IN ('STORE_OPERATOR', 'SYSTEM')),
    CONSTRAINT ck_schedule_audit_outcome
        CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_schedule_audit_conflict_check
        CHECK (conflict_check_status IN ('NOT_EVALUATED', 'EVALUATED')),
    INDEX idx_schedule_audit_store_occurred (store_id, occurred_at),
    INDEX idx_schedule_audit_target
        (store_id, schedule_stream, target_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
