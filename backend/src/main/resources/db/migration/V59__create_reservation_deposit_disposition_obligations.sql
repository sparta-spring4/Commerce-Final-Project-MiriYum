-- Issue #239: Reservation-owned cancellation disposition obligation runtime.
CREATE TABLE reservation_deposit_disposition_obligations (
    reservation_deposit_disposition_obligation_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_deposit_process_id BIGINT NOT NULL,
    reservation_id BIGINT NOT NULL,
    payment_id VARCHAR(64) NOT NULL,
    source_event_id VARCHAR(100) NOT NULL,
    source_event_type VARCHAR(40) NOT NULL,
    corrects_source_event_id VARCHAR(100) NULL,
    policy_version BIGINT NOT NULL,
    responsibility_code VARCHAR(32) NOT NULL,
    target_refund_rate_basis_points INT NOT NULL,
    obligation_key CHAR(36) NOT NULL,
    cancellation_idempotency_key CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    next_operation VARCHAR(8) NOT NULL,
    attempt_count INT NOT NULL,
    next_attempt_at DATETIME(6) NULL,
    lease_owner VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    claim_token BIGINT NOT NULL,
    last_attempted_at DATETIME(6) NULL,
    disposition_id VARCHAR(64) NULL,
    refund_id VARCHAR(64) NULL,
    original_amount_minor BIGINT NULL,
    target_refund_amount_minor BIGINT NULL,
    incremental_refund_amount_minor BIGINT NULL,
    completed_refund_amount_minor BIGINT NULL,
    withheld_amount_minor BIGINT NULL,
    currency CHAR(3) NULL,
    payment_disposition_status VARCHAR(32) NULL,
    failure_classification VARCHAR(16) NULL,
    payment_requested_at DATETIME(6) NULL,
    payment_updated_at DATETIME(6) NULL,
    payment_completed_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (reservation_deposit_disposition_obligation_id),
    CONSTRAINT uk_reservation_deposit_disposition_payment_event
        UNIQUE (payment_id, source_event_id),
    CONSTRAINT uk_reservation_deposit_disposition_obligation_key
        UNIQUE (obligation_key),
    CONSTRAINT uk_reservation_deposit_disposition_cancellation_key
        UNIQUE (cancellation_idempotency_key),
    CONSTRAINT chk_reservation_deposit_disposition_payment_id
        CHECK (payment_id REGEXP '^[1-9][0-9]{0,18}$'),
    CONSTRAINT chk_reservation_deposit_disposition_policy
        CHECK (policy_version = 2),
    CONSTRAINT chk_reservation_deposit_disposition_responsibility
        CHECK (responsibility_code IN (
            'CONSUMER', 'STORE_RESPONSIBLE', 'PLATFORM_RESPONSIBLE'
        )),
    CONSTRAINT chk_reservation_deposit_disposition_rate
        CHECK (target_refund_rate_basis_points IN (0, 5000, 10000)),
    CONSTRAINT chk_reservation_deposit_disposition_obligation_uuid
        CHECK (obligation_key REGEXP
               '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT chk_reservation_deposit_disposition_cancellation_uuid
        CHECK (cancellation_idempotency_key REGEXP
               '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT chk_reservation_deposit_disposition_attempts
        CHECK (attempt_count >= 0 AND claim_token >= 0),
    CONSTRAINT chk_reservation_deposit_disposition_lease CHECK (
        (status = 'PENDING' AND next_operation = 'APPLY'
            AND next_attempt_at IS NOT NULL
            AND lease_owner IS NULL AND lease_until IS NULL
            AND completed_at IS NULL)
        OR (status = 'PROCESSING' AND next_attempt_at IS NULL
            AND lease_owner IS NOT NULL AND lease_until IS NOT NULL
            AND attempt_count >= 1 AND completed_at IS NULL)
        OR (status = 'RECONCILIATION_REQUIRED' AND next_operation = 'QUERY'
            AND next_attempt_at IS NOT NULL
            AND lease_owner IS NULL AND lease_until IS NULL
            AND completed_at IS NULL)
        OR (status IN ('COMPLETED', 'RECOVERY_REQUIRED')
            AND next_attempt_at IS NULL
            AND lease_owner IS NULL AND lease_until IS NULL
            AND completed_at IS NOT NULL)
    ),
    CONSTRAINT chk_reservation_deposit_disposition_snapshot CHECK (
        (disposition_id IS NULL
            AND refund_id IS NULL
            AND original_amount_minor IS NULL
            AND target_refund_amount_minor IS NULL
            AND incremental_refund_amount_minor IS NULL
            AND completed_refund_amount_minor IS NULL
            AND withheld_amount_minor IS NULL
            AND currency IS NULL
            AND payment_disposition_status IS NULL
            AND failure_classification IS NULL
            AND payment_requested_at IS NULL
            AND payment_updated_at IS NULL
            AND payment_completed_at IS NULL)
        OR (disposition_id REGEXP
                '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            AND original_amount_minor > 0
            AND target_refund_amount_minor BETWEEN 0 AND original_amount_minor
            AND incremental_refund_amount_minor BETWEEN 0 AND original_amount_minor
            AND completed_refund_amount_minor BETWEEN 0 AND original_amount_minor
            AND withheld_amount_minor BETWEEN 0 AND original_amount_minor
            AND currency REGEXP '^[A-Z]{3}$'
            AND payment_disposition_status IN (
                'PROCESSING', 'COMPLETED', 'FAILED', 'RECONCILIATION_REQUIRED'
            )
            AND (failure_classification IS NULL
                 OR failure_classification IN ('RETRYABLE', 'PERMANENT', 'UNKNOWN'))
            AND payment_requested_at IS NOT NULL
            AND payment_updated_at IS NOT NULL)
    ),
    INDEX idx_reservation_deposit_disposition_due
        (status, next_attempt_at, lease_until,
         reservation_deposit_disposition_obligation_id),
    INDEX idx_reservation_deposit_disposition_reservation_latest
        (reservation_id, reservation_deposit_disposition_obligation_id DESC)
) ENGINE = InnoDB;
