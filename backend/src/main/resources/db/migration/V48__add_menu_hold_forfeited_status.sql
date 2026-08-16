ALTER TABLE menu_holds
    DROP CHECK ck_menu_holds_parent_and_status,
    ADD CONSTRAINT ck_menu_holds_parent_and_status
        CHECK (
            (reservation_hold_id IS NULL
                AND expires_at IS NULL
                AND reservation_id IS NOT NULL
                AND status IN ('CONFIRMED', 'RELEASED', 'FULFILLED', 'FORFEITED'))
            OR
            (reservation_hold_id IS NOT NULL
                AND expires_at IS NOT NULL
                AND reservation_id IS NULL
                AND status IN ('ACTIVE', 'RECONCILIATION_REQUIRED', 'RELEASED', 'EXPIRED'))
            OR
            (reservation_hold_id IS NOT NULL
                AND expires_at IS NOT NULL
                AND reservation_id IS NOT NULL
                AND status IN ('CONFIRMED', 'RELEASED', 'FULFILLED', 'FORFEITED'))
        );
