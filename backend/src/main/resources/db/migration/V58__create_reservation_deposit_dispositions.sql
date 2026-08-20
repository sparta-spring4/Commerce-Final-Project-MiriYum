-- Issue #239: Payment-owned reservation deposit disposition runtime.
ALTER TABLE payment_refunds
    ADD COLUMN processing_started_at DATETIME(6) NULL AFTER requested_at,
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 1 AFTER updated_at,
    ADD CONSTRAINT chk_payment_refunds_attempt_count CHECK (attempt_count >= 1);

UPDATE payment_refunds
   SET processing_started_at = requested_at;

ALTER TABLE payment_refunds
    MODIFY COLUMN processing_started_at DATETIME(6) NOT NULL;

ALTER TABLE payment_ledger_entries
    DROP CHECK chk_payment_ledger_entries_type,
    ADD CONSTRAINT chk_payment_ledger_entries_type CHECK (entry_type IN (
        'PAYMENT_PREPARED', 'PAYMENT_CONFIRMED', 'PAYMENT_FAILED',
        'PAYMENT_RECONCILIATION_REQUIRED', 'REFUND_REQUESTED',
        'REFUND_RETRY_REQUESTED', 'REFUND_COMPLETED', 'REFUND_FAILED',
        'REFUND_RECONCILIATION_REQUIRED'
    ));

CREATE TABLE reservation_deposit_dispositions (
    reservation_deposit_disposition_pk BIGINT NOT NULL AUTO_INCREMENT,
    disposition_id CHAR(36) NOT NULL,
    payment_pk BIGINT NOT NULL,
    source_event_id VARCHAR(100) NOT NULL,
    source_event_type VARCHAR(40) NOT NULL,
    corrects_source_event_id VARCHAR(100) NULL,
    policy_version BIGINT NOT NULL,
    responsibility_code VARCHAR(32) NOT NULL,
    target_refund_rate_basis_points INT NOT NULL,
    original_amount_minor BIGINT NOT NULL,
    target_refund_amount_minor BIGINT NOT NULL,
    incremental_refund_amount_minor BIGINT NOT NULL,
    completed_refund_amount_minor BIGINT NOT NULL,
    withheld_amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    refund_id VARCHAR(19) NULL,
    idempotency_key CHAR(36) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_classification VARCHAR(16) NULL,
    correction_parent_claim VARCHAR(100) GENERATED ALWAYS AS (
        CASE
            WHEN corrects_source_event_id IS NULL
                OR (status = 'FAILED' AND failure_classification = 'PERMANENT')
                THEN NULL
            ELSE corrects_source_event_id
        END
    ) STORED,
    attempt_count INT NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (reservation_deposit_disposition_pk),
    CONSTRAINT uk_reservation_deposit_dispositions_id UNIQUE (disposition_id),
    CONSTRAINT uk_reservation_deposit_dispositions_idempotency
        UNIQUE (payment_pk, idempotency_key),
    CONSTRAINT uk_reservation_deposit_dispositions_source_event
        UNIQUE (payment_pk, source_event_id),
    CONSTRAINT uk_reservation_deposit_dispositions_correction_parent
        UNIQUE (payment_pk, correction_parent_claim),
    CONSTRAINT uk_reservation_deposit_dispositions_refund UNIQUE (refund_id),
    CONSTRAINT fk_reservation_deposit_dispositions_payment
        FOREIGN KEY (payment_pk) REFERENCES payments (payment_pk),
    CONSTRAINT fk_reservation_deposit_dispositions_refund
        FOREIGN KEY (refund_id) REFERENCES payment_refunds (refund_id),
    CONSTRAINT chk_reservation_deposit_dispositions_id
        CHECK (disposition_id REGEXP
               '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT chk_reservation_deposit_dispositions_identity
        CHECK (disposition_id = idempotency_key),
    CONSTRAINT chk_reservation_deposit_dispositions_policy
        CHECK (policy_version > 0),
    CONSTRAINT chk_reservation_deposit_dispositions_responsibility
        CHECK (responsibility_code IN (
            'CONSUMER', 'STORE_RESPONSIBLE', 'PLATFORM_RESPONSIBLE'
        )),
    CONSTRAINT chk_reservation_deposit_dispositions_rate
        CHECK (target_refund_rate_basis_points IN (0, 5000, 10000)),
    CONSTRAINT chk_reservation_deposit_dispositions_amounts CHECK (
        original_amount_minor > 0
        AND target_refund_amount_minor BETWEEN 0 AND original_amount_minor
        AND completed_refund_amount_minor BETWEEN 0 AND original_amount_minor
        AND incremental_refund_amount_minor BETWEEN 0 AND original_amount_minor
        AND withheld_amount_minor = original_amount_minor - target_refund_amount_minor
        AND (
            (status = 'COMPLETED'
                AND completed_refund_amount_minor = target_refund_amount_minor)
            OR (status <> 'COMPLETED'
                AND completed_refund_amount_minor <= target_refund_amount_minor
                AND completed_refund_amount_minor + incremental_refund_amount_minor
                    = target_refund_amount_minor)
            OR (status = 'FAILED'
                AND completed_refund_amount_minor > target_refund_amount_minor
                AND incremental_refund_amount_minor = 0)
        )
    ),
    CONSTRAINT chk_reservation_deposit_dispositions_currency
        CHECK (currency REGEXP '^[A-Z]{3}$'),
    CONSTRAINT chk_reservation_deposit_dispositions_status CHECK (
        (status = 'PROCESSING' AND failure_classification IS NULL
            AND completed_at IS NULL AND attempt_count >= 1)
        OR (status = 'COMPLETED' AND failure_classification IS NULL
            AND completed_at IS NOT NULL AND attempt_count >= 0)
        OR (status = 'FAILED' AND failure_classification IN ('RETRYABLE', 'PERMANENT')
            AND completed_at IS NULL AND attempt_count >= 0)
        OR (status = 'RECONCILIATION_REQUIRED' AND failure_classification = 'UNKNOWN'
            AND completed_at IS NULL AND attempt_count >= 1)
    ),
    INDEX idx_reservation_deposit_dispositions_payment
        (payment_pk, requested_at ASC),
    INDEX idx_reservation_deposit_dispositions_correction_audit
        (payment_pk, corrects_source_event_id, requested_at ASC)
) ENGINE = InnoDB;
