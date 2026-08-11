ALTER TABLE reservations
    ADD COLUMN cancellation_policy_version BIGINT NULL
        AFTER reservation_policy_version,
    ADD CONSTRAINT ck_reservations_cancellation_policy_version
        CHECK (
            cancellation_policy_version IS NULL
            OR cancellation_policy_version > 0
        );
