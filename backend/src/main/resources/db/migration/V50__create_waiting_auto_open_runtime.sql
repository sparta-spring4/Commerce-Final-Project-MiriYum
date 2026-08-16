-- Issue #380: durable AUTO reception-open jobs and immutable OPEN effects.
CREATE TABLE waiting_auto_open_jobs (
    waiting_auto_open_job_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    business_interval_key VARCHAR(128) NOT NULL,
    business_date DATE NOT NULL,
    interval_starts_at DATETIME(6) NOT NULL,
    interval_ends_at DATETIME(6) NOT NULL,
    scheduled_at DATETIME(6) NOT NULL,
    expected_settings_version BIGINT NOT NULL,
    expected_advance_open_minutes INT NOT NULL,
    idempotency_key CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    lease_owner VARCHAR(128) NULL,
    lease_until DATETIME(6) NULL,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    failure_code VARCHAR(64) NULL,
    last_attempted_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (waiting_auto_open_job_id),
    CONSTRAINT uk_waiting_auto_open_interval_version
        UNIQUE (store_id, business_interval_key, expected_settings_version),
    CONSTRAINT uk_waiting_auto_open_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_waiting_auto_open_store FOREIGN KEY (store_id)
        REFERENCES stores (store_id) ON DELETE RESTRICT,
    CONSTRAINT ck_waiting_auto_open_status CHECK (status IN (
        'PENDING',
        'PROCESSING',
        'RETRY_WAIT',
        'COMPLETED',
        'INVALIDATED',
        'RECONCILIATION_REQUIRED'
    )),
    CONSTRAINT ck_waiting_auto_open_interval
        CHECK (interval_starts_at < interval_ends_at),
    CONSTRAINT ck_waiting_auto_open_settings_version
        CHECK (expected_settings_version >= 1),
    CONSTRAINT ck_waiting_auto_open_advance
        CHECK (expected_advance_open_minutes BETWEEN 0 AND 180),
    CONSTRAINT ck_waiting_auto_open_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_waiting_auto_open_fence CHECK (fencing_token >= 0),
    CONSTRAINT ck_waiting_auto_open_lease CHECK (
        (status = 'PROCESSING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR
        (status <> 'PROCESSING' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_waiting_auto_open_next_attempt CHECK (
        (status IN ('PENDING', 'RETRY_WAIT') AND next_attempt_at IS NOT NULL)
        OR
        (status NOT IN ('PENDING', 'RETRY_WAIT') AND next_attempt_at IS NULL)
    ),
    CONSTRAINT ck_waiting_auto_open_completion CHECK (
        (status IN ('COMPLETED', 'INVALIDATED', 'RECONCILIATION_REQUIRED')
            AND completed_at IS NOT NULL)
        OR
        (status NOT IN ('COMPLETED', 'INVALIDATED', 'RECONCILIATION_REQUIRED')
            AND completed_at IS NULL)
    ),
    INDEX idx_waiting_auto_open_due (
        status,
        next_attempt_at,
        scheduled_at,
        waiting_auto_open_job_id
    ),
    INDEX idx_waiting_auto_open_expired_lease (
        status,
        lease_until,
        waiting_auto_open_job_id
    ),
    INDEX idx_waiting_auto_open_invalidation (
        store_id,
        status,
        expected_settings_version
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE waiting_reception_windows (
    waiting_reception_window_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    business_interval_key VARCHAR(128) NOT NULL,
    business_date DATE NOT NULL,
    accepting_from DATETIME(6) NOT NULL,
    accepting_until DATETIME(6) NOT NULL,
    opened_settings_version BIGINT NOT NULL,
    opened_by_job_id BIGINT NOT NULL,
    opened_at DATETIME(6) NOT NULL,
    PRIMARY KEY (waiting_reception_window_id),
    CONSTRAINT uk_waiting_reception_interval_version
        UNIQUE (store_id, business_interval_key, opened_settings_version),
    CONSTRAINT fk_waiting_reception_store FOREIGN KEY (store_id)
        REFERENCES stores (store_id) ON DELETE RESTRICT,
    CONSTRAINT fk_waiting_reception_job FOREIGN KEY (opened_by_job_id)
        REFERENCES waiting_auto_open_jobs (waiting_auto_open_job_id) ON DELETE RESTRICT,
    CONSTRAINT ck_waiting_reception_interval
        CHECK (accepting_from < accepting_until),
    CONSTRAINT ck_waiting_reception_settings_version
        CHECK (opened_settings_version >= 1),
    INDEX idx_waiting_reception_current (
        store_id,
        business_date,
        opened_settings_version,
        accepting_from,
        accepting_until
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
