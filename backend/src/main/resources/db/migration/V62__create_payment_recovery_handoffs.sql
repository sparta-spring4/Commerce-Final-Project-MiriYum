-- Issue #457: reliable Reservation-to-Payment manual recovery handoff.
CREATE TABLE payment_recovery_handoffs (
    payment_recovery_handoff_id BIGINT NOT NULL AUTO_INCREMENT,
    source_type VARCHAR(48) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    payment_pk BIGINT NOT NULL,
    payment_id VARCHAR(64) NOT NULL,
    source_event_id VARCHAR(100) NOT NULL,
    recovery_kind VARCHAR(40) NOT NULL,
    registration_idempotency_key CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    lease_owner VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    claim_token BIGINT NOT NULL DEFAULT 0,
    admin_case_id VARCHAR(100) NULL,
    acknowledged_at DATETIME(6) NULL,
    operation_id CHAR(36) NULL,
    operation_status VARCHAR(20) NULL,
    operation_started_at DATETIME(6) NULL,
    operation_finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (payment_recovery_handoff_id),
    CONSTRAINT fk_payment_recovery_handoff_payment
        FOREIGN KEY (payment_pk) REFERENCES payments (payment_pk),
    CONSTRAINT uk_payment_recovery_handoff_source
        UNIQUE (source_type, source_id),
    CONSTRAINT uk_payment_recovery_handoff_registration
        UNIQUE (registration_idempotency_key),
    CONSTRAINT chk_payment_recovery_handoff_source_type CHECK (
        source_type IN (
            'RESERVATION_DEPOSIT_DISPOSITION',
            'RESERVATION_DEPOSIT_REFUND'
        )
    ),
    CONSTRAINT chk_payment_recovery_handoff_source_id
        CHECK (source_id REGEXP '^[1-9][0-9]{0,18}$'),
    CONSTRAINT chk_payment_recovery_handoff_payment_id
        CHECK (payment_id REGEXP '^[1-9][0-9]{0,18}$'),
    CONSTRAINT chk_payment_recovery_handoff_kind CHECK (
        recovery_kind IN (
            'DISPOSITION_RESULT_UNKNOWN', 'DISPOSITION_FAILED',
            'REFUND_RESULT_UNKNOWN', 'REFUND_FAILED'
        )
    ),
    CONSTRAINT chk_payment_recovery_handoff_registration_uuid CHECK (
        registration_idempotency_key REGEXP
        '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    CONSTRAINT chk_payment_recovery_handoff_claim_token
        CHECK (claim_token >= 0),
    CONSTRAINT chk_payment_recovery_handoff_state CHECK (
        (status = 'AVAILABLE'
            AND lease_owner IS NULL AND lease_until IS NULL
            AND admin_case_id IS NULL AND acknowledged_at IS NULL)
        OR (status = 'CLAIMED'
            AND lease_owner IS NOT NULL AND lease_until IS NOT NULL
            AND claim_token > 0
            AND admin_case_id IS NULL AND acknowledged_at IS NULL)
        OR (status = 'ACKNOWLEDGED'
            AND lease_owner IS NULL AND lease_until IS NULL
            AND admin_case_id IS NOT NULL AND acknowledged_at IS NOT NULL)
    ),
    CONSTRAINT chk_payment_recovery_handoff_operation CHECK (
        (operation_id IS NULL AND operation_status IS NULL
            AND operation_started_at IS NULL AND operation_finished_at IS NULL)
        OR (operation_id REGEXP
                '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            AND operation_status = 'PROCESSING'
            AND operation_started_at IS NOT NULL
            AND operation_finished_at IS NULL)
        OR (operation_id REGEXP
                '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            AND operation_status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')
            AND operation_started_at IS NOT NULL
            AND operation_finished_at IS NOT NULL)
    ),
    INDEX idx_payment_recovery_handoff_claim
        (status, lease_until, payment_recovery_handoff_id)
) ENGINE = InnoDB;

CREATE TABLE reservation_payment_recovery_outbox (
    reservation_payment_recovery_outbox_id BIGINT NOT NULL AUTO_INCREMENT,
    source_type VARCHAR(48) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    payment_id VARCHAR(64) NOT NULL,
    source_event_id VARCHAR(100) NOT NULL,
    delivery_idempotency_key CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    lease_owner VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    claim_token BIGINT NOT NULL DEFAULT 0,
    delivered_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (reservation_payment_recovery_outbox_id),
    CONSTRAINT uk_reservation_payment_recovery_outbox_source
        UNIQUE (source_type, source_id),
    CONSTRAINT uk_reservation_payment_recovery_outbox_delivery
        UNIQUE (delivery_idempotency_key),
    CONSTRAINT chk_reservation_payment_recovery_outbox_source_type CHECK (
        source_type IN (
            'RESERVATION_DEPOSIT_DISPOSITION',
            'RESERVATION_DEPOSIT_REFUND'
        )
    ),
    CONSTRAINT chk_reservation_payment_recovery_outbox_source_id
        CHECK (source_id REGEXP '^[1-9][0-9]{0,18}$'),
    CONSTRAINT chk_reservation_payment_recovery_outbox_payment_id
        CHECK (payment_id REGEXP '^[1-9][0-9]{0,18}$'),
    CONSTRAINT chk_reservation_payment_recovery_outbox_delivery_uuid CHECK (
        delivery_idempotency_key REGEXP
        '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    CONSTRAINT chk_reservation_payment_recovery_outbox_attempts
        CHECK (attempt_count >= 0 AND claim_token >= 0),
    CONSTRAINT chk_reservation_payment_recovery_outbox_state CHECK (
        (status = 'PENDING'
            AND next_attempt_at IS NOT NULL
            AND lease_owner IS NULL AND lease_until IS NULL
            AND delivered_at IS NULL)
        OR (status = 'PROCESSING'
            AND next_attempt_at IS NULL
            AND lease_owner IS NOT NULL AND lease_until IS NOT NULL
            AND attempt_count > 0 AND claim_token > 0
            AND delivered_at IS NULL)
        OR (status = 'DELIVERED'
            AND next_attempt_at IS NULL
            AND lease_owner IS NULL AND lease_until IS NULL
            AND delivered_at IS NOT NULL)
    ),
    INDEX idx_reservation_payment_recovery_outbox_due
        (status, next_attempt_at, lease_until,
         reservation_payment_recovery_outbox_id)
) ENGINE = InnoDB;
