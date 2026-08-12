-- Issue #264: ReservationHold persistence contract.
CREATE TABLE reservation_holds (
    reservation_hold_id BIGINT NOT NULL AUTO_INCREMENT,
    consumer_account_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    store_name_snapshot VARCHAR(100) NOT NULL,
    service_date DATE NOT NULL,
    start_at DATETIME(6) NOT NULL,
    service_end_at DATETIME(6) NOT NULL,
    occupancy_end_at DATETIME(6) NOT NULL,
    time_zone_id_snapshot VARCHAR(64) NOT NULL,
    start_offset_seconds INT NOT NULL,
    service_end_offset_seconds INT NOT NULL,
    occupancy_end_offset_seconds INT NOT NULL,
    slot_interval_minutes INT NOT NULL,
    service_duration_minutes INT NOT NULL,
    turnover_duration_minutes INT NOT NULL,
    reservation_time_policy_store_id BIGINT NOT NULL,
    reservation_policy_version BIGINT NOT NULL,
    adult_count INT NOT NULL,
    child_count INT NOT NULL,
    infant_count INT NOT NULL,
    notification_target_reference VARCHAR(512) NOT NULL,
    contact_available_at_confirmation BOOLEAN NOT NULL,
    capacity_policy_version BIGINT NOT NULL,
    cancellation_policy_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    status_version BIGINT NOT NULL DEFAULT 0,
    creation_command_id VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reservation_hold_id),
    CONSTRAINT uk_reservation_holds_creation_command
        UNIQUE (creation_command_id),
    CONSTRAINT uk_reservation_holds_id_created
        UNIQUE (reservation_hold_id, created_at),
    CONSTRAINT fk_reservation_holds_consumer_account
        FOREIGN KEY (consumer_account_id)
        REFERENCES consumer_accounts (consumer_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_holds_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_holds_store_name
        CHECK (CHAR_LENGTH(TRIM(store_name_snapshot)) BETWEEN 1 AND 100),
    CONSTRAINT ck_reservation_holds_time_snapshot
        CHECK (
            CHAR_LENGTH(TRIM(time_zone_id_snapshot)) BETWEEN 1 AND 64
            AND reservation_time_policy_store_id = store_id
            AND reservation_policy_version > 0
            AND slot_interval_minutes BETWEEN 1 AND 1440
            AND service_duration_minutes BETWEEN 1 AND 1440
            AND turnover_duration_minutes BETWEEN 0 AND 1440
            AND service_duration_minutes + turnover_duration_minutes <= 1440
            AND SECOND(start_at) = 0
            AND MICROSECOND(start_at) = 0
            AND start_at < service_end_at
            AND service_end_at <= occupancy_end_at
            AND service_end_at = TIMESTAMPADD(
                MINUTE,
                service_duration_minutes,
                start_at
            )
            AND occupancy_end_at = TIMESTAMPADD(
                MINUTE,
                turnover_duration_minutes,
                service_end_at
            )
        ),
    CONSTRAINT ck_reservation_holds_party_counts
        CHECK (
            adult_count BETWEEN 0 AND 100
            AND child_count BETWEEN 0 AND 100
            AND infant_count BETWEEN 0 AND 100
            AND adult_count + child_count + infant_count >= 1
        ),
    CONSTRAINT ck_reservation_holds_contact
        CHECK (
            CHAR_LENGTH(TRIM(notification_target_reference)) BETWEEN 1 AND 512
            AND contact_available_at_confirmation = TRUE
        ),
    CONSTRAINT ck_reservation_holds_policy_versions
        CHECK (
            capacity_policy_version > 0
            AND cancellation_policy_version > 0
        ),
    CONSTRAINT ck_reservation_holds_status
        CHECK (status IN (
            'ACTIVE',
            'RECONCILIATION_REQUIRED',
            'CONFIRMED',
            'RELEASED',
            'EXPIRED'
        )),
    CONSTRAINT ck_reservation_holds_status_version
        CHECK (status_version >= 0),
    CONSTRAINT ck_reservation_holds_creation_command
        CHECK (CHAR_LENGTH(TRIM(creation_command_id)) BETWEEN 1 AND 100),
    CONSTRAINT ck_reservation_holds_expiration
        CHECK (expires_at = TIMESTAMPADD(MINUTE, 10, created_at)),
    INDEX idx_reservation_holds_consumer_service (
        consumer_account_id,
        service_date,
        reservation_hold_id
    ),
    INDEX idx_reservation_holds_expiration (
        status,
        expires_at,
        reservation_hold_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reservation_hold_capacity_allocations (
    reservation_hold_capacity_allocation_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_hold_id BIGINT NOT NULL,
    reservation_capacity_bucket_id BIGINT NOT NULL,
    occupied_people INT NOT NULL,
    occupied_teams INT NOT NULL,
    capacity_policy_version BIGINT NOT NULL,
    PRIMARY KEY (reservation_hold_capacity_allocation_id),
    CONSTRAINT uk_reservation_hold_capacity_allocations_hold_bucket
        UNIQUE (reservation_hold_id, reservation_capacity_bucket_id),
    CONSTRAINT fk_reservation_hold_capacity_allocations_hold
        FOREIGN KEY (reservation_hold_id)
        REFERENCES reservation_holds (reservation_hold_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_hold_capacity_allocations_bucket
        FOREIGN KEY (reservation_capacity_bucket_id)
        REFERENCES reservation_capacity_buckets (reservation_capacity_bucket_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_hold_capacity_allocations_people
        CHECK (occupied_people > 0),
    CONSTRAINT ck_reservation_hold_capacity_allocations_teams
        CHECK (occupied_teams = 1),
    CONSTRAINT ck_reservation_hold_capacity_allocations_policy
        CHECK (capacity_policy_version > 0),
    INDEX idx_reservation_hold_capacity_allocations_bucket (
        reservation_capacity_bucket_id,
        reservation_hold_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reservation_hold_transition_audits (
    reservation_hold_transition_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_hold_id BIGINT NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_id BIGINT NULL,
    requested_at DATETIME(6) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    before_status VARCHAR(32) NULL,
    after_status VARCHAR(32) NOT NULL,
    reservation_time_policy_version BIGINT NOT NULL,
    capacity_policy_version BIGINT NOT NULL,
    command_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (reservation_hold_transition_audit_id),
    CONSTRAINT uk_reservation_hold_transition_audits_command
        UNIQUE (command_id),
    CONSTRAINT fk_reservation_hold_transition_audits_hold
        FOREIGN KEY (reservation_hold_id)
        REFERENCES reservation_holds (reservation_hold_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_hold_transition_audits_actor
        CHECK (
            CHAR_LENGTH(TRIM(actor_type)) BETWEEN 1 AND 32
            AND (
                (actor_type = 'SYSTEM' AND (actor_id IS NULL OR actor_id > 0))
                OR (actor_type <> 'SYSTEM' AND actor_id IS NOT NULL AND actor_id > 0)
            )
        ),
    CONSTRAINT ck_reservation_hold_transition_audits_time
        CHECK (occurred_at >= requested_at),
    CONSTRAINT ck_reservation_hold_transition_audits_status
        CHECK (
            (before_status IS NULL AND after_status = 'ACTIVE')
            OR (
                before_status IN (
                    'ACTIVE',
                    'RECONCILIATION_REQUIRED',
                    'CONFIRMED',
                    'RELEASED',
                    'EXPIRED'
                )
                AND after_status IN (
                    'ACTIVE',
                    'RECONCILIATION_REQUIRED',
                    'CONFIRMED',
                    'RELEASED',
                    'EXPIRED'
                )
                AND before_status <> after_status
            )
        ),
    CONSTRAINT ck_reservation_hold_transition_audits_policy
        CHECK (
            reservation_time_policy_version > 0
            AND capacity_policy_version > 0
        ),
    CONSTRAINT ck_reservation_hold_transition_audits_command
        CHECK (CHAR_LENGTH(TRIM(command_id)) BETWEEN 1 AND 100),
    INDEX idx_reservation_hold_transition_audits_hold (
        reservation_hold_id,
        reservation_hold_transition_audit_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reservation_hold_warning_tasks (
    reservation_hold_warning_task_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_hold_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    warning_due_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reservation_hold_warning_task_id),
    CONSTRAINT uk_reservation_hold_warning_tasks_hold
        UNIQUE (reservation_hold_id),
    CONSTRAINT fk_reservation_hold_warning_tasks_hold
        FOREIGN KEY (reservation_hold_id, created_at)
        REFERENCES reservation_holds (reservation_hold_id, created_at)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_hold_warning_tasks_due
        CHECK (warning_due_at = TIMESTAMPADD(MINUTE, 8, created_at)),
    INDEX idx_reservation_hold_warning_tasks_due (
        warning_due_at,
        reservation_hold_warning_task_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
