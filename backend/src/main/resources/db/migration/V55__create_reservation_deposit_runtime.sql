-- Issue #238: Reservation-owned reservation deposit orchestration runtime.
CREATE TABLE reservation_deposit_processes (
    reservation_deposit_process_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_hold_id BIGINT NOT NULL,
    consumer_account_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    status_version BIGINT NOT NULL DEFAULT 0,
    expires_at DATETIME(6) NOT NULL,
    payment_id VARCHAR(64) NOT NULL,
    portone_payment_id VARCHAR(100) NOT NULL,
    payment_order_name VARCHAR(100) NOT NULL,
    payment_amount_minor BIGINT NOT NULL,
    payment_currency CHAR(3) NOT NULL,
    payment_source_expires_at DATETIME(6) NOT NULL,
    payment_preparation_status VARCHAR(16) NOT NULL,
    store_deposit_policy_version BIGINT NOT NULL,
    deposit_rate_percent INT NOT NULL,
    deposit_algorithm_version BIGINT NOT NULL,
    deposit_party_size INT NOT NULL,
    deposit_amount_minor BIGINT NOT NULL,
    deposit_currency CHAR(3) NOT NULL,
    representative_menu_version BIGINT NOT NULL,
    representative_menu_price_total BIGINT NOT NULL,
    representative_menu_count INT NOT NULL,
    abandonment_requested BOOLEAN NOT NULL,
    abandonment_requested_at DATETIME(6) NULL,
    resources_protected BOOLEAN NOT NULL,
    resources_protected_at DATETIME(6) NULL,
    final_reservation_id BIGINT NULL,
    requested_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    reconciliation_next_attempt_at DATETIME(6) NULL,
    reconciliation_lease_owner VARCHAR(64) NULL,
    reconciliation_lease_until DATETIME(6) NULL,
    reconciliation_claim_token BIGINT NOT NULL DEFAULT 0,
    reconciliation_last_attempted_at DATETIME(6) NULL,
    PRIMARY KEY (reservation_deposit_process_id),
    CONSTRAINT uk_reservation_deposit_process_hold
        UNIQUE (reservation_hold_id),
    CONSTRAINT uk_reservation_deposit_process_payment
        UNIQUE (payment_id),
    CONSTRAINT uk_reservation_deposit_process_final_reservation
        UNIQUE (final_reservation_id),
    CONSTRAINT fk_reservation_deposit_process_hold
        FOREIGN KEY (reservation_hold_id)
        REFERENCES reservation_holds (reservation_hold_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_deposit_process_consumer
        FOREIGN KEY (consumer_account_id)
        REFERENCES consumer_accounts (consumer_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_deposit_process_final_reservation
        FOREIGN KEY (final_reservation_id)
        REFERENCES reservations (reservation_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_deposit_process_status
        CHECK (status IN (
            'AWAITING_PAYMENT',
            'FINALIZING_RESOURCES',
            'COMPLETED',
            'ABANDONED',
            'EXPIRED',
            'COMPENSATION_REQUIRED',
            'COMPENSATING',
            'COMPENSATED',
            'RECOVERY_REQUIRED'
        )),
    CONSTRAINT ck_reservation_deposit_process_version
        CHECK (status_version >= 0),
    CONSTRAINT ck_reservation_deposit_process_payment_snapshot
        CHECK (
            CHAR_LENGTH(TRIM(payment_id)) BETWEEN 1 AND 64
            AND CHAR_LENGTH(TRIM(portone_payment_id)) BETWEEN 1 AND 100
            AND CHAR_LENGTH(TRIM(payment_order_name)) BETWEEN 1 AND 100
            AND payment_amount_minor > 0
            AND payment_currency REGEXP '^[A-Z]{3}$'
            AND payment_source_expires_at = expires_at
            AND payment_preparation_status = 'READY'
        ),
    CONSTRAINT ck_reservation_deposit_process_calculation_snapshot
        CHECK (
            store_deposit_policy_version > 0
            AND deposit_rate_percent BETWEEN 1 AND 100
            AND deposit_algorithm_version > 0
            AND deposit_party_size > 0
            AND deposit_amount_minor = payment_amount_minor
            AND deposit_currency = payment_currency
            AND representative_menu_version > 0
            AND representative_menu_price_total > 0
            AND representative_menu_count > 0
        ),
    CONSTRAINT ck_reservation_deposit_process_abandonment
        CHECK (
            (abandonment_requested = FALSE AND abandonment_requested_at IS NULL)
            OR (abandonment_requested = TRUE AND abandonment_requested_at IS NOT NULL)
        ),
    CONSTRAINT ck_reservation_deposit_process_protection
        CHECK (
            (resources_protected = FALSE AND resources_protected_at IS NULL)
            OR (resources_protected = TRUE AND resources_protected_at IS NOT NULL)
        ),
    CONSTRAINT ck_reservation_deposit_process_completion
        CHECK (
            (status = 'COMPLETED'
                AND final_reservation_id IS NOT NULL
                AND completed_at IS NOT NULL)
            OR (status IN ('ABANDONED', 'EXPIRED', 'COMPENSATED')
                AND final_reservation_id IS NULL
                AND completed_at IS NOT NULL)
            OR (status NOT IN ('COMPLETED', 'ABANDONED', 'EXPIRED', 'COMPENSATED')
                AND final_reservation_id IS NULL
                AND completed_at IS NULL)
        ),
    CONSTRAINT ck_reservation_deposit_process_reconciliation_lease
        CHECK (
            reconciliation_claim_token >= 0
            AND (
                (reconciliation_lease_owner IS NULL
                    AND reconciliation_lease_until IS NULL)
                OR (CHAR_LENGTH(TRIM(reconciliation_lease_owner)) BETWEEN 1 AND 64
                    AND reconciliation_lease_until IS NOT NULL
                    AND reconciliation_next_attempt_at IS NULL)
            )
        ),
    INDEX idx_reservation_deposit_process_reconciliation_due (
        status,
        reconciliation_next_attempt_at,
        reconciliation_lease_until,
        reservation_deposit_process_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reservation_deposit_calculation_items (
    reservation_deposit_process_id BIGINT NOT NULL,
    menu_id VARCHAR(64) NOT NULL,
    published_version_number INT NOT NULL,
    base_price INT NOT NULL,
    CONSTRAINT uk_reservation_deposit_calculation_item
        UNIQUE (reservation_deposit_process_id, menu_id),
    CONSTRAINT fk_reservation_deposit_calculation_item_process
        FOREIGN KEY (reservation_deposit_process_id)
        REFERENCES reservation_deposit_processes (reservation_deposit_process_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_deposit_calculation_item
        CHECK (
            CHAR_LENGTH(TRIM(menu_id)) BETWEEN 1 AND 64
            AND published_version_number > 0
            AND base_price >= 0
        )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reservation_deposit_cause_audits (
    reservation_deposit_cause_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_deposit_process_id BIGINT NOT NULL,
    cause_code VARCHAR(40) NOT NULL,
    payment_id VARCHAR(64) NOT NULL,
    payment_status VARCHAR(32) NOT NULL,
    paid_at DATETIME(6) NULL,
    observed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reservation_deposit_cause_audit_id),
    CONSTRAINT uk_reservation_deposit_cause_process_code
        UNIQUE (reservation_deposit_process_id, cause_code),
    CONSTRAINT fk_reservation_deposit_cause_process
        FOREIGN KEY (reservation_deposit_process_id)
        REFERENCES reservation_deposit_processes (reservation_deposit_process_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_deposit_cause_text
        CHECK (
            CHAR_LENGTH(TRIM(cause_code)) BETWEEN 1 AND 40
            AND CHAR_LENGTH(TRIM(payment_id)) BETWEEN 1 AND 64
            AND CHAR_LENGTH(TRIM(payment_status)) BETWEEN 1 AND 32
        ),
    INDEX idx_reservation_deposit_cause_process (
        reservation_deposit_process_id,
        reservation_deposit_cause_audit_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reservation_deposit_refund_obligations (
    reservation_deposit_refund_obligation_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_deposit_process_id BIGINT NOT NULL,
    payment_id VARCHAR(64) NOT NULL,
    refund_amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    refund_policy_version BIGINT NOT NULL,
    source_event_id VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(36) NOT NULL,
    reason_code VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL,
    next_attempt_at DATETIME(6) NULL,
    lease_owner VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    claim_token BIGINT NOT NULL,
    last_attempted_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (reservation_deposit_refund_obligation_id),
    CONSTRAINT uk_reservation_deposit_refund_obligation_identity
        UNIQUE (reservation_deposit_process_id, payment_id, reason_code),
    CONSTRAINT fk_reservation_deposit_refund_process
        FOREIGN KEY (reservation_deposit_process_id)
        REFERENCES reservation_deposit_processes (reservation_deposit_process_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_deposit_refund_identity
        CHECK (
            CHAR_LENGTH(TRIM(payment_id)) BETWEEN 1 AND 64
            AND refund_amount_minor > 0
            AND currency REGEXP '^[A-Z]{3}$'
            AND refund_policy_version > 0
            AND CHAR_LENGTH(TRIM(source_event_id)) BETWEEN 1 AND 100
            AND idempotency_key REGEXP
                '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            AND CHAR_LENGTH(TRIM(reason_code)) BETWEEN 1 AND 40
        ),
    CONSTRAINT ck_reservation_deposit_refund_status
        CHECK (status IN (
            'REQUIRED',
            'PROCESSING',
            'COMPLETED',
            'RECONCILIATION_REQUIRED'
        )),
    CONSTRAINT ck_reservation_deposit_refund_attempt
        CHECK (
            attempt_count >= 0
            AND claim_token >= 0
            AND row_version >= 0
            AND (
                (attempt_count = 0 AND last_attempted_at IS NULL)
                OR (attempt_count > 0 AND last_attempted_at IS NOT NULL)
            )
        ),
    CONSTRAINT ck_reservation_deposit_refund_lifecycle
        CHECK (
            (status = 'REQUIRED'
                AND next_attempt_at IS NOT NULL
                AND lease_owner IS NULL
                AND lease_until IS NULL
                AND completed_at IS NULL)
            OR (status = 'PROCESSING'
                AND next_attempt_at IS NULL
                AND CHAR_LENGTH(TRIM(lease_owner)) BETWEEN 1 AND 64
                AND lease_until IS NOT NULL
                AND completed_at IS NULL)
            OR (status IN ('COMPLETED', 'RECONCILIATION_REQUIRED')
                AND next_attempt_at IS NULL
                AND lease_owner IS NULL
                AND lease_until IS NULL
                AND completed_at IS NOT NULL)
        ),
    INDEX idx_reservation_deposit_refund_due (
        status,
        next_attempt_at,
        lease_until,
        reservation_deposit_refund_obligation_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
