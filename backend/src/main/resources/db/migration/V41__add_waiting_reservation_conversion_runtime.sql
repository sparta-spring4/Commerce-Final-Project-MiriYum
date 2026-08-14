-- Issue #272: Waiting-owned payment-backed reservation conversion lifecycle.
ALTER TABLE waiting_teams
    ADD COLUMN reservation_converting_at DATETIME(6) NULL AFTER closed_by_store_at,
    ADD COLUMN waiting_payment_id VARCHAR(19) NULL AFTER reservation_converting_at,
    ADD COLUMN reservation_reference_id BIGINT NULL AFTER waiting_payment_id,
    ADD COLUMN reservation_converted_at DATETIME(6) NULL AFTER reservation_reference_id;

ALTER TABLE waiting_teams
    DROP CHECK ck_waiting_teams_status,
    DROP CHECK ck_waiting_teams_call_window,
    ADD CONSTRAINT ck_waiting_teams_status
        CHECK (status IN (
            'WAITING',
            'CALLED',
            'ARRIVED',
            'CHECKED_IN',
            'CANCELLED',
            'NO_SHOW',
            'CLOSED_BY_STORE',
            'RESERVATION_CONVERTING',
            'RESERVATION_CONVERTED'
        )),
    ADD CONSTRAINT ck_waiting_teams_call_window
        CHECK (
            COALESCE((CASE status
                WHEN 'WAITING' THEN
                    called_at IS NULL
                    AND arrival_deadline IS NULL
                    AND arrived_at IS NULL
                    AND checked_in_at IS NULL
                    AND cancelled_at IS NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NULL
                WHEN 'CALLED' THEN
                    called_at IS NOT NULL
                    AND called_at >= created_at
                    AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                    AND arrived_at IS NULL
                    AND checked_in_at IS NULL
                    AND cancelled_at IS NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NULL
                WHEN 'ARRIVED' THEN
                    called_at IS NOT NULL
                    AND called_at >= created_at
                    AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                    AND arrived_at BETWEEN called_at AND arrival_deadline
                    AND checked_in_at IS NULL
                    AND cancelled_at IS NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NULL
                WHEN 'CHECKED_IN' THEN
                    called_at IS NOT NULL
                    AND called_at >= created_at
                    AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                    AND arrived_at BETWEEN called_at AND arrival_deadline
                    AND checked_in_at >= arrived_at
                    AND cancelled_at IS NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NULL
                WHEN 'CANCELLED' THEN
                    checked_in_at IS NULL
                    AND cancelled_at IS NOT NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NULL
                    AND (
                        (
                            called_at IS NULL
                            AND arrival_deadline IS NULL
                            AND arrived_at IS NULL
                            AND cancelled_at >= created_at
                        )
                        OR (
                            called_at IS NOT NULL
                            AND called_at >= created_at
                            AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                            AND arrived_at IS NULL
                            AND cancelled_at >= called_at
                        )
                        OR (
                            called_at IS NOT NULL
                            AND called_at >= created_at
                            AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                            AND arrived_at BETWEEN called_at AND arrival_deadline
                            AND cancelled_at >= arrived_at
                        )
                    )
                WHEN 'NO_SHOW' THEN
                    called_at IS NOT NULL
                    AND called_at >= created_at
                    AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                    AND arrived_at IS NULL
                    AND checked_in_at IS NULL
                    AND cancelled_at IS NULL
                    AND no_show_at >= arrival_deadline
                    AND closed_by_store_at IS NULL
                WHEN 'CLOSED_BY_STORE' THEN
                    checked_in_at IS NULL
                    AND cancelled_at IS NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NOT NULL
                    AND (
                        (
                            called_at IS NULL
                            AND arrival_deadline IS NULL
                            AND arrived_at IS NULL
                            AND closed_by_store_at >= created_at
                        )
                        OR (
                            called_at IS NOT NULL
                            AND called_at >= created_at
                            AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                            AND arrived_at IS NULL
                            AND closed_by_store_at >= called_at
                        )
                        OR (
                            called_at IS NOT NULL
                            AND called_at >= created_at
                            AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                            AND arrived_at BETWEEN called_at AND arrival_deadline
                            AND closed_by_store_at >= arrived_at
                        )
                    )
                WHEN 'RESERVATION_CONVERTING' THEN
                    called_at IS NULL
                    AND arrival_deadline IS NULL
                    AND arrived_at IS NULL
                    AND checked_in_at IS NULL
                    AND cancelled_at IS NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NULL
                WHEN 'RESERVATION_CONVERTED' THEN
                    called_at IS NULL
                    AND arrival_deadline IS NULL
                    AND arrived_at IS NULL
                    AND checked_in_at IS NULL
                    AND cancelled_at IS NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NULL
                ELSE FALSE
            END), FALSE) = TRUE
        );

ALTER TABLE waiting_teams
    ADD CONSTRAINT ck_waiting_teams_reservation_conversion
        CHECK (
            COALESCE((CASE status
                WHEN 'RESERVATION_CONVERTING' THEN
                    reservation_reference_id IS NULL
                    AND reservation_converted_at IS NULL
                    AND (
                        (
                            reservation_converting_at IS NULL
                            AND waiting_payment_id IS NULL
                        )
                        OR (
                            reservation_converting_at IS NOT NULL
                            AND reservation_converting_at >= created_at
                            AND REGEXP_LIKE(waiting_payment_id, '^[1-9][0-9]{0,18}$')
                        )
                    )
                WHEN 'RESERVATION_CONVERTED' THEN
                    reservation_converting_at IS NOT NULL
                    AND reservation_converting_at >= created_at
                    AND REGEXP_LIKE(waiting_payment_id, '^[1-9][0-9]{0,18}$')
                    AND reservation_reference_id > 0
                    AND reservation_converted_at >= reservation_converting_at
                WHEN 'CANCELLED' THEN
                    reservation_reference_id IS NULL
                    AND reservation_converted_at IS NULL
                    AND (
                        (
                            reservation_converting_at IS NULL
                            AND waiting_payment_id IS NULL
                        )
                        OR (
                            reservation_converting_at IS NOT NULL
                            AND reservation_converting_at >= created_at
                            AND reservation_converting_at <= cancelled_at
                            AND REGEXP_LIKE(waiting_payment_id, '^[1-9][0-9]{0,18}$')
                        )
                    )
                WHEN 'CLOSED_BY_STORE' THEN
                    reservation_reference_id IS NULL
                    AND reservation_converted_at IS NULL
                    AND (
                        (
                            reservation_converting_at IS NULL
                            AND waiting_payment_id IS NULL
                        )
                        OR (
                            reservation_converting_at IS NOT NULL
                            AND reservation_converting_at >= created_at
                            AND reservation_converting_at <= closed_by_store_at
                            AND REGEXP_LIKE(waiting_payment_id, '^[1-9][0-9]{0,18}$')
                        )
                    )
                ELSE
                    reservation_converting_at IS NULL
                    AND waiting_payment_id IS NULL
                    AND reservation_reference_id IS NULL
                    AND reservation_converted_at IS NULL
            END), FALSE) = TRUE
        );

ALTER TABLE waiting_transition_audits
    DROP CHECK ck_waiting_transition_audits_status,
    ADD CONSTRAINT ck_waiting_transition_audits_status
        CHECK (
            (before_status IS NULL AND after_status = 'WAITING')
            OR (
                before_status IN (
                    'WAITING', 'CALLED', 'ARRIVED', 'CHECKED_IN', 'CANCELLED',
                    'NO_SHOW', 'CLOSED_BY_STORE', 'RESERVATION_CONVERTING',
                    'RESERVATION_CONVERTED'
                )
                AND after_status IN (
                    'WAITING', 'CALLED', 'ARRIVED', 'CHECKED_IN', 'CANCELLED',
                    'NO_SHOW', 'CLOSED_BY_STORE', 'RESERVATION_CONVERTING',
                    'RESERVATION_CONVERTED'
                )
                AND before_status <> after_status
            )
        );

ALTER TABLE waiting_status_events
    DROP CHECK ck_waiting_status_events_public_status,
    ADD CONSTRAINT ck_waiting_status_events_public_status
        CHECK (public_status IN (
            'WAITING',
            'CALLED',
            'ARRIVED',
            'CHECKED_IN',
            'CANCELLED',
            'NO_SHOW',
            'CLOSED_BY_STORE',
            'RESERVATION_CONVERTING',
            'RESERVATION_CONVERTED'
        ));

CREATE TABLE waiting_conversion_compensations (
    waiting_conversion_compensation_id BIGINT NOT NULL AUTO_INCREMENT,
    waiting_team_id BIGINT NOT NULL,
    payment_id VARCHAR(19) NOT NULL,
    refund_amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    refund_policy_version BIGINT NOT NULL,
    source_event_id VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(36) NOT NULL,
    reason_code VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    lease_owner VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    claim_token BIGINT NOT NULL DEFAULT 0,
    last_attempted_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (waiting_conversion_compensation_id),
    CONSTRAINT uk_waiting_conversion_compensations_team_payment
        UNIQUE (waiting_team_id, payment_id),
    CONSTRAINT uk_waiting_conversion_compensations_source_event
        UNIQUE (source_event_id),
    CONSTRAINT uk_waiting_conversion_compensations_idempotency
        UNIQUE (idempotency_key),
    INDEX idx_waiting_conversion_compensations_claimable (
        status,
        next_attempt_at,
        lease_until,
        waiting_conversion_compensation_id
    ),
    CONSTRAINT fk_waiting_conversion_compensations_team
        FOREIGN KEY (waiting_team_id)
        REFERENCES waiting_teams (waiting_team_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_waiting_conversion_compensations_payment_id
        CHECK (REGEXP_LIKE(payment_id, '^[1-9][0-9]{0,18}$')),
    CONSTRAINT ck_waiting_conversion_compensations_amount
        CHECK (refund_amount_minor > 0),
    CONSTRAINT ck_waiting_conversion_compensations_currency
        CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$')),
    CONSTRAINT ck_waiting_conversion_compensations_policy_version
        CHECK (refund_policy_version > 0),
    CONSTRAINT ck_waiting_conversion_compensations_source_event
        CHECK (CHAR_LENGTH(TRIM(source_event_id)) BETWEEN 1 AND 100),
    CONSTRAINT ck_waiting_conversion_compensations_idempotency_key
        CHECK (REGEXP_LIKE(
            idempotency_key,
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        )),
    CONSTRAINT ck_waiting_conversion_compensations_reason
        CHECK (CHAR_LENGTH(TRIM(reason_code)) BETWEEN 1 AND 40),
    CONSTRAINT ck_waiting_conversion_compensations_status
        CHECK (status IN (
            'PENDING',
            'PROCESSING',
            'COMPLETED',
            'RECONCILIATION_REQUIRED'
        )),
    CONSTRAINT ck_waiting_conversion_compensations_fence
        CHECK (
            attempt_count >= 0
            AND claim_token >= 0
            AND claim_token = attempt_count
        ),
    CONSTRAINT ck_waiting_conversion_compensations_lifecycle
        CHECK (
            COALESCE((CASE status
                WHEN 'PENDING' THEN
                    next_attempt_at IS NOT NULL
                    AND next_attempt_at >= created_at
                    AND lease_owner IS NULL
                    AND lease_until IS NULL
                    AND completed_at IS NULL
                    AND (
                        (attempt_count = 0 AND last_attempted_at IS NULL)
                        OR (
                            attempt_count > 0
                            AND last_attempted_at IS NOT NULL
                            AND last_attempted_at >= created_at
                        )
                    )
                WHEN 'PROCESSING' THEN
                    attempt_count > 0
                    AND next_attempt_at IS NULL
                    AND lease_owner IS NOT NULL
                    AND CHAR_LENGTH(TRIM(lease_owner)) BETWEEN 1 AND 64
                    AND last_attempted_at IS NOT NULL
                    AND last_attempted_at >= created_at
                    AND lease_until IS NOT NULL
                    AND lease_until > last_attempted_at
                    AND completed_at IS NULL
                WHEN 'COMPLETED' THEN
                    attempt_count > 0
                    AND next_attempt_at IS NULL
                    AND lease_owner IS NULL
                    AND lease_until IS NULL
                    AND last_attempted_at IS NOT NULL
                    AND last_attempted_at >= created_at
                    AND completed_at IS NOT NULL
                    AND completed_at >= last_attempted_at
                WHEN 'RECONCILIATION_REQUIRED' THEN
                    attempt_count > 0
                    AND next_attempt_at IS NULL
                    AND lease_owner IS NULL
                    AND lease_until IS NULL
                    AND last_attempted_at IS NOT NULL
                    AND last_attempted_at >= created_at
                    AND completed_at IS NOT NULL
                    AND completed_at >= last_attempted_at
                ELSE FALSE
            END), FALSE) = TRUE
        )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
