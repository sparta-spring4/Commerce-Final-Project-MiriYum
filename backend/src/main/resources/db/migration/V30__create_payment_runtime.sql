CREATE TABLE payment_public_ids (
    id BIGINT NOT NULL AUTO_INCREMENT,
    PRIMARY KEY (id)
) ENGINE = InnoDB AUTO_INCREMENT = 900000000000000001;

CREATE TABLE refund_public_ids (
    id BIGINT NOT NULL AUTO_INCREMENT,
    PRIMARY KEY (id)
) ENGINE = InnoDB AUTO_INCREMENT = 910000000000000001;

CREATE TABLE payments (
    payment_pk BIGINT NOT NULL AUTO_INCREMENT,
    payment_id VARCHAR(19) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_reference_id VARCHAR(19) NOT NULL,
    source_policy_version BIGINT NOT NULL,
    source_expires_at DATETIME(6) NOT NULL,
    preparation_idempotency_key VARCHAR(36) NOT NULL,
    preparation_request_fingerprint CHAR(64) NOT NULL,
    consumer_account_id BIGINT NOT NULL,
    amount_minor BIGINT NOT NULL,
    refunded_amount_minor BIGINT NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL,
    portone_payment_id VARCHAR(64) NOT NULL,
    order_name VARCHAR(100) NOT NULL,
    provider_transaction_id VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL,
    last_attempt_status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    paid_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (payment_pk),
    CONSTRAINT uk_payments_payment_id UNIQUE (payment_id),
    CONSTRAINT uk_payments_source UNIQUE (source_type, source_reference_id),
    CONSTRAINT uk_payments_preparation_idempotency
        UNIQUE (source_type, preparation_idempotency_key),
    CONSTRAINT uk_payments_portone_payment_id UNIQUE (portone_payment_id),
    CONSTRAINT uk_payments_provider_transaction_id UNIQUE (provider_transaction_id),
    CONSTRAINT fk_payments_consumer_account
        FOREIGN KEY (consumer_account_id)
        REFERENCES consumer_accounts (consumer_account_id),
    CONSTRAINT chk_payments_payment_id
        CHECK (payment_id REGEXP '^[1-9][0-9]{0,18}$'),
    CONSTRAINT chk_payments_source_reference_id
        CHECK (source_reference_id REGEXP '^[1-9][0-9]{0,18}$'),
    CONSTRAINT chk_payments_source_policy_version CHECK (source_policy_version > 0),
    CONSTRAINT chk_payments_source_expiration CHECK (source_expires_at > created_at),
    CONSTRAINT chk_payments_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT chk_payments_refunded_amount
        CHECK (refunded_amount_minor >= 0 AND refunded_amount_minor <= amount_minor),
    CONSTRAINT chk_payments_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
    CONSTRAINT chk_payments_status CHECK (status IN (
        'READY', 'CONFIRMING', 'PAID', 'PARTIALLY_REFUNDED',
        'REFUNDED', 'RECONCILIATION_REQUIRED'
    )),
    CONSTRAINT chk_payments_attempt_status CHECK (last_attempt_status IN (
        'NOT_STARTED', 'PENDING', 'PAID', 'FAILED', 'CANCELLED', 'UNKNOWN'
    )),
    INDEX idx_payments_consumer_history (
        consumer_account_id, created_at DESC, payment_id DESC
    ),
    INDEX idx_payments_consumer_status_history (
        consumer_account_id, status, created_at DESC, payment_id DESC
    )
) ENGINE = InnoDB;

CREATE TABLE payment_attempts (
    payment_attempt_id BIGINT NOT NULL AUTO_INCREMENT,
    payment_pk BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    principal_id BIGINT NOT NULL,
    idempotency_key VARCHAR(36) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    provider_transaction_id VARCHAR(255) NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (payment_attempt_id),
    CONSTRAINT uk_payment_attempts_number UNIQUE (payment_pk, attempt_no),
    CONSTRAINT uk_payment_attempts_idempotency UNIQUE (principal_id, idempotency_key),
    CONSTRAINT uk_payment_attempts_transaction UNIQUE (provider_transaction_id),
    CONSTRAINT fk_payment_attempts_payment
        FOREIGN KEY (payment_pk) REFERENCES payments (payment_pk),
    CONSTRAINT chk_payment_attempts_number CHECK (attempt_no > 0),
    CONSTRAINT chk_payment_attempts_status CHECK (status IN (
        'NOT_STARTED', 'PENDING', 'PAID', 'FAILED', 'CANCELLED', 'UNKNOWN'
    )),
    INDEX idx_payment_attempts_payment (payment_pk, started_at DESC)
) ENGINE = InnoDB;

CREATE TABLE payment_refunds (
    payment_refund_pk BIGINT NOT NULL AUTO_INCREMENT,
    refund_id VARCHAR(19) NOT NULL,
    payment_pk BIGINT NOT NULL,
    idempotency_key VARCHAR(36) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    source_event_id VARCHAR(100) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(40) NOT NULL,
    policy_version BIGINT NOT NULL,
    provider_cancellation_id VARCHAR(255) NULL,
    requested_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (payment_refund_pk),
    CONSTRAINT uk_payment_refunds_refund_id UNIQUE (refund_id),
    CONSTRAINT uk_payment_refunds_idempotency UNIQUE (payment_pk, idempotency_key),
    CONSTRAINT uk_payment_refunds_source_event UNIQUE (payment_pk, source_event_id),
    CONSTRAINT uk_payment_refunds_provider_cancellation UNIQUE (provider_cancellation_id),
    CONSTRAINT fk_payment_refunds_payment
        FOREIGN KEY (payment_pk) REFERENCES payments (payment_pk),
    CONSTRAINT chk_payment_refunds_refund_id
        CHECK (refund_id REGEXP '^[1-9][0-9]{0,18}$'),
    CONSTRAINT chk_payment_refunds_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT chk_payment_refunds_policy_version CHECK (policy_version > 0),
    CONSTRAINT chk_payment_refunds_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
    CONSTRAINT chk_payment_refunds_status CHECK (status IN (
        'REQUESTED', 'VALIDATING', 'PROCESSING', 'COMPLETED',
        'FAILED', 'RECONCILIATION_REQUIRED'
    )),
    INDEX idx_payment_refunds_payment (payment_pk, requested_at ASC)
) ENGINE = InnoDB;

CREATE TABLE payment_ledger_entries (
    payment_ledger_entry_id BIGINT NOT NULL AUTO_INCREMENT,
    payment_pk BIGINT NOT NULL,
    payment_refund_pk BIGINT NULL,
    event_key VARCHAR(160) NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (payment_ledger_entry_id),
    CONSTRAINT uk_payment_ledger_entries_event UNIQUE (event_key),
    CONSTRAINT fk_payment_ledger_entries_payment
        FOREIGN KEY (payment_pk) REFERENCES payments (payment_pk),
    CONSTRAINT fk_payment_ledger_entries_refund
        FOREIGN KEY (payment_refund_pk) REFERENCES payment_refunds (payment_refund_pk),
    CONSTRAINT chk_payment_ledger_entries_type CHECK (entry_type IN (
        'PAYMENT_PREPARED', 'PAYMENT_CONFIRMED', 'PAYMENT_FAILED',
        'PAYMENT_RECONCILIATION_REQUIRED', 'REFUND_REQUESTED',
        'REFUND_COMPLETED', 'REFUND_FAILED', 'REFUND_RECONCILIATION_REQUIRED'
    )),
    CONSTRAINT chk_payment_ledger_entries_amount CHECK (amount_minor >= 0),
    CONSTRAINT chk_payment_ledger_entries_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
    INDEX idx_payment_ledger_entries_payment (payment_pk, occurred_at ASC)
) ENGINE = InnoDB;

CREATE TABLE payment_webhook_receipts (
    webhook_message_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    body_sha256 CHAR(64) NOT NULL,
    portone_payment_id VARCHAR(64) NULL,
    provider_transaction_id VARCHAR(255) NULL,
    provider_cancellation_id VARCHAR(255) NULL,
    outcome VARCHAR(32) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    PRIMARY KEY (webhook_message_id),
    CONSTRAINT chk_payment_webhook_receipts_outcome CHECK (outcome IN (
        'RECEIVED', 'PROCESSING', 'PROCESSED', 'IGNORED', 'RECONCILIATION_REQUIRED'
    )),
    INDEX idx_payment_webhook_receipts_payment (portone_payment_id, received_at DESC)
) ENGINE = InnoDB;
