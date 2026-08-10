CREATE TABLE reservation_cancellation_audits (
    reservation_cancellation_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_id BIGINT NOT NULL,
    cancellation_reason VARCHAR(500) NULL,
    requested_at DATETIME(6) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    before_status VARCHAR(20) NOT NULL,
    after_status VARCHAR(20) NOT NULL,
    cancellation_policy_version BIGINT NOT NULL,
    capacity_policy_version BIGINT NOT NULL,
    command_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (reservation_cancellation_audit_id),
    CONSTRAINT uk_reservation_cancellation_audits_reservation UNIQUE (reservation_id),
    CONSTRAINT fk_reservation_cancellation_audits_reservation
        FOREIGN KEY (reservation_id) REFERENCES reservations (reservation_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_cancellation_audits_actor_type
        CHECK (actor_type IN ('CONSUMER', 'STORE_OPERATOR')),
    CONSTRAINT ck_reservation_cancellation_audits_actor_id CHECK (actor_id > 0),
    CONSTRAINT ck_reservation_cancellation_audits_reason CHECK (
        (cancellation_reason IS NULL OR CHAR_LENGTH(cancellation_reason) BETWEEN 1 AND 500)
        AND (actor_type <> 'STORE_OPERATOR' OR cancellation_reason IS NOT NULL)
    ),
    CONSTRAINT ck_reservation_cancellation_audits_time CHECK (occurred_at >= requested_at),
    CONSTRAINT ck_reservation_cancellation_audits_transition
        CHECK (before_status = 'CONFIRMED' AND after_status = 'CANCELLED'),
    CONSTRAINT ck_reservation_cancellation_audits_versions
        CHECK (cancellation_policy_version > 0 AND capacity_policy_version > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
