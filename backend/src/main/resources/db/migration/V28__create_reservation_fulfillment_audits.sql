CREATE TABLE reservation_fulfillment_audits (
    reservation_fulfillment_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_id BIGINT NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    before_status VARCHAR(20) NOT NULL,
    after_status VARCHAR(20) NOT NULL,
    reservation_time_policy_version BIGINT NOT NULL,
    capacity_policy_version BIGINT NOT NULL,
    command_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (reservation_fulfillment_audit_id),
    CONSTRAINT uk_reservation_fulfillment_audits_reservation UNIQUE (reservation_id),
    CONSTRAINT fk_reservation_fulfillment_audits_reservation
        FOREIGN KEY (reservation_id) REFERENCES reservations (reservation_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_fulfillment_audits_actor_type
        CHECK (actor_type = 'STORE_OPERATOR'),
    CONSTRAINT ck_reservation_fulfillment_audits_actor_id CHECK (actor_id > 0),
    CONSTRAINT ck_reservation_fulfillment_audits_time CHECK (occurred_at >= requested_at),
    CONSTRAINT ck_reservation_fulfillment_audits_transition
        CHECK (before_status = 'CONFIRMED' AND after_status = 'FULFILLED'),
    CONSTRAINT ck_reservation_fulfillment_audits_versions
        CHECK (reservation_time_policy_version > 0 AND capacity_policy_version > 0),
    CONSTRAINT ck_reservation_fulfillment_audits_command_id
        CHECK (CHAR_LENGTH(TRIM(command_id)) BETWEEN 1 AND 100)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
