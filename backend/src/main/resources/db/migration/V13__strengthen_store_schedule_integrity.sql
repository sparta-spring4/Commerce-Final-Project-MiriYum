ALTER TABLE store_operating_schedule_versions
    ADD CONSTRAINT uk_operating_schedule_store_id
        UNIQUE (store_id, operating_schedule_version_id),
    ADD CONSTRAINT ck_operating_schedule_conflict_result
        CHECK (
            (conflict_check_status = 'NOT_EVALUATED'
                AND conflict_count IS NULL)
            OR
            (conflict_check_status = 'EVALUATED'
                AND conflict_count IS NOT NULL
                AND conflict_count >= 0)
        ),
    ADD INDEX idx_operating_schedule_global_due
        (status, effective_at, version_number);

ALTER TABLE store_reservation_schedule_versions
    ADD CONSTRAINT uk_reservation_schedule_store_id
        UNIQUE (store_id, reservation_schedule_version_id),
    ADD CONSTRAINT fk_reservation_schedule_store_operating
        FOREIGN KEY (store_id, validated_operating_version_id)
        REFERENCES store_operating_schedule_versions (
            store_id, operating_schedule_version_id
        )
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_reservation_schedule_conflict_result
        CHECK (
            (conflict_check_status = 'NOT_EVALUATED'
                AND conflict_count IS NULL)
            OR
            (conflict_check_status = 'EVALUATED'
                AND conflict_count IS NOT NULL
                AND conflict_count >= 0)
        ),
    ADD INDEX idx_reservation_schedule_global_due
        (status, effective_at, version_number);

ALTER TABLE store_schedule_state
    ADD CONSTRAINT fk_schedule_state_store_operating
        FOREIGN KEY (store_id, active_operating_schedule_version_id)
        REFERENCES store_operating_schedule_versions (
            store_id, operating_schedule_version_id
        )
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_schedule_state_store_reservation
        FOREIGN KEY (store_id, active_reservation_schedule_version_id)
        REFERENCES store_reservation_schedule_versions (
            store_id, reservation_schedule_version_id
        )
        ON DELETE RESTRICT;

ALTER TABLE store_schedule_audit_events
    ADD CONSTRAINT ck_schedule_audit_conflict_result
        CHECK (
            (conflict_check_status = 'NOT_EVALUATED'
                AND conflict_count IS NULL)
            OR
            (conflict_check_status = 'EVALUATED'
                AND conflict_count IS NOT NULL
                AND conflict_count >= 0)
        );
