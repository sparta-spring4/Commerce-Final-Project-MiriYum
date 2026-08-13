ALTER TABLE menu_holds DROP CHECK ck_menu_holds_status;

ALTER TABLE menu_holds MODIFY reservation_id BIGINT NULL;
ALTER TABLE menu_holds MODIFY status VARCHAR(32) NOT NULL;
ALTER TABLE menu_holds ADD reservation_hold_id BIGINT NULL;
ALTER TABLE menu_holds ADD expires_at DATETIME(6) NULL;

ALTER TABLE reservation_holds
    ADD CONSTRAINT uk_reservation_holds_id_expires
    UNIQUE (reservation_hold_id, expires_at);

ALTER TABLE menu_holds
    ADD CONSTRAINT uk_menu_holds_reservation_hold
        UNIQUE (reservation_hold_id),
    ADD CONSTRAINT fk_menu_holds_reservation_hold_expiration
        FOREIGN KEY (reservation_hold_id, expires_at)
        REFERENCES reservation_holds (reservation_hold_id, expires_at)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_menu_holds_parent_and_status
        CHECK (
            (reservation_hold_id IS NULL
                AND expires_at IS NULL
                AND reservation_id IS NOT NULL
                AND status IN ('CONFIRMED', 'RELEASED', 'FULFILLED'))
            OR
            (reservation_hold_id IS NOT NULL
                AND expires_at IS NOT NULL
                AND reservation_id IS NULL
                AND status IN ('ACTIVE', 'RECONCILIATION_REQUIRED', 'RELEASED', 'EXPIRED'))
            OR
            (reservation_hold_id IS NOT NULL
                AND expires_at IS NOT NULL
                AND reservation_id IS NOT NULL
                AND status IN ('CONFIRMED', 'RELEASED', 'FULFILLED'))
        );
