-- Flyway 순서: #70 V14 이후 V15 reservation core.
CREATE TABLE reservations (
    reservation_id BIGINT NOT NULL AUTO_INCREMENT,
    consumer_account_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    store_name_snapshot VARCHAR(100) NOT NULL,
    service_date DATE NOT NULL,
    start_time TIME(6) NOT NULL,
    end_time TIME(6) NOT NULL,
    adult_count INT NOT NULL,
    child_count INT NOT NULL,
    infant_count INT NOT NULL,
    notification_target_reference VARCHAR(512) NOT NULL,
    contact_available_at_confirmation BOOLEAN NOT NULL,
    capacity_policy_version BIGINT NOT NULL,
    reservation_policy_version BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    cancelled_at DATETIME(6) NULL,
    fulfilled_at DATETIME(6) NULL,
    PRIMARY KEY (reservation_id),
    CONSTRAINT fk_reservations_consumer_account
        FOREIGN KEY (consumer_account_id)
        REFERENCES consumer_accounts (consumer_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_reservations_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservations_store_name_snapshot
        CHECK (CHAR_LENGTH(TRIM(store_name_snapshot)) BETWEEN 1 AND 100),
    CONSTRAINT ck_reservations_service_time
        CHECK (start_time < end_time),
    CONSTRAINT ck_reservations_party_counts
        CHECK (
            adult_count BETWEEN 0 AND 100
            AND child_count BETWEEN 0 AND 100
            AND infant_count BETWEEN 0 AND 100
            AND adult_count + child_count + infant_count >= 1
        ),
    CONSTRAINT ck_reservations_notification_target_reference
        CHECK (
            CHAR_LENGTH(TRIM(notification_target_reference)) BETWEEN 1 AND 512
        ),
    CONSTRAINT ck_reservations_contact_available_at_confirmation
        CHECK (contact_available_at_confirmation = TRUE),
    CONSTRAINT ck_reservations_policy_versions
        CHECK (
            capacity_policy_version > 0
            AND reservation_policy_version > 0
        ),
    CONSTRAINT ck_reservations_status
        CHECK (status IN ('CONFIRMED', 'CANCELLED', 'FULFILLED')),
    CONSTRAINT ck_reservations_terminal_timestamps
        CHECK (
            (
                status = 'CONFIRMED'
                AND cancelled_at IS NULL
                AND fulfilled_at IS NULL
            )
            OR (
                status = 'CANCELLED'
                AND cancelled_at IS NOT NULL
                AND fulfilled_at IS NULL
                AND cancelled_at >= created_at
            )
            OR (
                status = 'FULFILLED'
                AND cancelled_at IS NULL
                AND fulfilled_at IS NOT NULL
                AND fulfilled_at >= created_at
            )
        ),
    INDEX idx_reservations_consumer_service (
        consumer_account_id,
        service_date,
        reservation_id
    ),
    INDEX idx_reservations_store_service (
        store_id,
        service_date,
        start_time,
        reservation_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reservation_capacity_buckets (
    reservation_capacity_bucket_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    service_date DATE NOT NULL,
    start_time TIME(6) NOT NULL,
    end_time TIME(6) NOT NULL,
    max_people INT NOT NULL,
    max_teams INT NOT NULL,
    occupied_people INT NOT NULL,
    occupied_teams INT NOT NULL,
    min_party_size INT NOT NULL,
    max_party_size INT NOT NULL,
    infants_allowed BOOLEAN NOT NULL,
    policy_version BIGINT NOT NULL,
    PRIMARY KEY (reservation_capacity_bucket_id),
    CONSTRAINT uk_reservation_capacity_buckets_business_key
        UNIQUE (
            store_id,
            service_date,
            start_time,
            end_time,
            policy_version
        ),
    CONSTRAINT fk_reservation_capacity_buckets_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_capacity_buckets_service_time
        CHECK (start_time < end_time),
    CONSTRAINT ck_reservation_capacity_buckets_capacity
        CHECK (
            max_people >= 0
            AND max_teams >= 0
            AND occupied_people >= 0
            AND occupied_teams >= 0
        ),
    CONSTRAINT ck_reservation_capacity_buckets_party_size
        CHECK (
            min_party_size > 0
            AND max_party_size > 0
            AND min_party_size <= max_party_size
            AND max_party_size <= max_people
        ),
    CONSTRAINT ck_reservation_capacity_buckets_policy_version
        CHECK (policy_version > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reservation_capacity_allocations (
    reservation_capacity_allocation_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    reservation_capacity_bucket_id BIGINT NOT NULL,
    occupied_people INT NOT NULL,
    occupied_teams INT NOT NULL,
    capacity_policy_version BIGINT NOT NULL,
    PRIMARY KEY (reservation_capacity_allocation_id),
    CONSTRAINT uk_reservation_capacity_allocations_reservation_bucket
        UNIQUE (reservation_id, reservation_capacity_bucket_id),
    CONSTRAINT fk_reservation_capacity_allocations_reservation
        FOREIGN KEY (reservation_id)
        REFERENCES reservations (reservation_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_capacity_allocations_bucket
        FOREIGN KEY (reservation_capacity_bucket_id)
        REFERENCES reservation_capacity_buckets (reservation_capacity_bucket_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_capacity_allocations_occupied_people
        CHECK (occupied_people > 0),
    CONSTRAINT ck_reservation_capacity_allocations_occupied_teams
        CHECK (occupied_teams = 1),
    CONSTRAINT ck_reservation_capacity_allocations_policy_version
        CHECK (capacity_policy_version > 0),
    INDEX idx_reservation_capacity_allocations_bucket (
        reservation_capacity_bucket_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
