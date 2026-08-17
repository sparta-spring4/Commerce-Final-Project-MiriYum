ALTER TABLE reservations
    DROP CHECK ck_reservations_status,
    DROP CHECK ck_reservations_terminal_timestamps,
    ADD COLUMN no_show_at DATETIME(6) NULL AFTER fulfilled_at,
    ADD CONSTRAINT ck_reservations_status
        CHECK (status IN ('CONFIRMED', 'CANCELLED', 'FULFILLED', 'NO_SHOW')),
    ADD CONSTRAINT ck_reservations_terminal_timestamps
        CHECK (
            (
                status = 'CONFIRMED'
                AND cancelled_at IS NULL
                AND fulfilled_at IS NULL
                AND no_show_at IS NULL
            )
            OR (
                status = 'CANCELLED'
                AND cancelled_at IS NOT NULL
                AND fulfilled_at IS NULL
                AND no_show_at IS NULL
                AND cancelled_at >= created_at
            )
            OR (
                status = 'FULFILLED'
                AND cancelled_at IS NULL
                AND fulfilled_at IS NOT NULL
                AND no_show_at IS NULL
                AND fulfilled_at >= created_at
            )
            OR (
                status = 'NO_SHOW'
                AND cancelled_at IS NULL
                AND fulfilled_at IS NULL
                AND no_show_at IS NOT NULL
                AND no_show_at >= created_at
            )
        );

CREATE TABLE reservation_check_in_qr_grants (
    reservation_check_in_qr_grant_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    token_version BIGINT NOT NULL,
    token_digest BINARY(32) NOT NULL,
    qr_epoch_account_id BIGINT NOT NULL,
    qr_epoch_opaque_version VARCHAR(46) NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    PRIMARY KEY (reservation_check_in_qr_grant_id),
    CONSTRAINT uk_reservation_check_in_qr_grants_reservation UNIQUE (reservation_id),
    CONSTRAINT uk_reservation_check_in_qr_grants_digest UNIQUE (token_digest),
    CONSTRAINT fk_reservation_check_in_qr_grants_reservation
        FOREIGN KEY (reservation_id) REFERENCES reservations (reservation_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_check_in_qr_grants_version
        CHECK (token_version > 0),
    CONSTRAINT ck_reservation_check_in_qr_grants_digest
        CHECK (OCTET_LENGTH(token_digest) = 32),
    CONSTRAINT ck_reservation_check_in_qr_grants_epoch
        CHECK (
            qr_epoch_account_id > 0
            AND qr_epoch_opaque_version REGEXP '^v1[.][A-Za-z0-9_-]{43}$'
        ),
    CONSTRAINT ck_reservation_check_in_qr_grants_ttl
        CHECK (expires_at = TIMESTAMPADD(SECOND, 30, issued_at)),
    CONSTRAINT ck_reservation_check_in_qr_grants_consumed_at
        CHECK (
            consumed_at IS NULL
            OR (consumed_at >= issued_at AND consumed_at < expires_at)
        )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reservation_check_in_audits (
    reservation_check_in_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_id BIGINT NOT NULL,
    token_version BIGINT NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    before_status VARCHAR(20) NOT NULL,
    after_status VARCHAR(20) NOT NULL,
    command_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (reservation_check_in_audit_id),
    CONSTRAINT uk_reservation_check_in_audits_event_version
        UNIQUE (reservation_id, event_type, token_version),
    CONSTRAINT uk_reservation_check_in_audits_command UNIQUE (command_id),
    CONSTRAINT fk_reservation_check_in_audits_reservation
        FOREIGN KEY (reservation_id) REFERENCES reservations (reservation_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_check_in_audits_store
        FOREIGN KEY (store_id) REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_check_in_audits_ids
        CHECK (store_id > 0 AND actor_id > 0 AND token_version > 0),
    CONSTRAINT ck_reservation_check_in_audits_time
        CHECK (occurred_at >= requested_at),
    CONSTRAINT ck_reservation_check_in_audits_event
        CHECK (
            (
                event_type = 'QR_GRANT_ISSUED'
                AND actor_type = 'CONSUMER'
                AND before_status = 'CONFIRMED'
                AND after_status = 'CONFIRMED'
            )
            OR (
                event_type = 'QR_CHECK_IN_FULFILLED'
                AND actor_type = 'STORE_OPERATOR'
                AND before_status = 'CONFIRMED'
                AND after_status = 'FULFILLED'
            )
        ),
    CONSTRAINT ck_reservation_check_in_audits_command
        CHECK (CHAR_LENGTH(TRIM(command_id)) BETWEEN 1 AND 100)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reservation_no_show_audits (
    reservation_no_show_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_id BIGINT NOT NULL,
    reason VARCHAR(48) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    before_status VARCHAR(20) NOT NULL,
    after_status VARCHAR(20) NOT NULL,
    reservation_time_policy_version BIGINT NOT NULL,
    capacity_policy_version BIGINT NOT NULL,
    command_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (reservation_no_show_audit_id),
    CONSTRAINT uk_reservation_no_show_audits_reservation UNIQUE (reservation_id),
    CONSTRAINT uk_reservation_no_show_audits_command UNIQUE (command_id),
    CONSTRAINT fk_reservation_no_show_audits_reservation
        FOREIGN KEY (reservation_id) REFERENCES reservations (reservation_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_no_show_audits_store
        FOREIGN KEY (store_id) REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_no_show_audits_actor
        CHECK (actor_type = 'STORE_OPERATOR' AND actor_id > 0),
    CONSTRAINT ck_reservation_no_show_audits_reason
        CHECK (reason IN (
            'USER_CAUSE_CANDIDATE',
            'STORE_CAUSE_CANDIDATE',
            'PLATFORM_EXTERNAL_CAUSE_CANDIDATE',
            'UNCLEAR'
        )),
    CONSTRAINT ck_reservation_no_show_audits_time
        CHECK (occurred_at >= requested_at),
    CONSTRAINT ck_reservation_no_show_audits_transition
        CHECK (before_status = 'CONFIRMED' AND after_status = 'NO_SHOW'),
    CONSTRAINT ck_reservation_no_show_audits_versions
        CHECK (reservation_time_policy_version > 0 AND capacity_policy_version > 0),
    CONSTRAINT ck_reservation_no_show_audits_command
        CHECK (CHAR_LENGTH(TRIM(command_id)) BETWEEN 1 AND 100)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
