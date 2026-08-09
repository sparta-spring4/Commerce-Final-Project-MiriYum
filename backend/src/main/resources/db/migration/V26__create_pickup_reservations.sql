CREATE TABLE pickup_reservations (
    pickup_reservation_id BIGINT NOT NULL AUTO_INCREMENT,
    consumer_account_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    store_name_snapshot VARCHAR(100) NOT NULL,
    time_zone_id_snapshot VARCHAR(100) NOT NULL,
    pickup_date DATE NOT NULL,
    pickup_time TIME(6) NOT NULL,
    pickup_at DATETIME(6) NOT NULL,
    acquire_operation_id VARCHAR(100) COLLATE utf8mb4_0900_as_cs NOT NULL,
    status VARCHAR(20) NOT NULL,
    cancelled_by VARCHAR(20) NULL,
    cancellation_reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    cancelled_at DATETIME(6) NULL,
    picked_up_at DATETIME(6) NULL,
    PRIMARY KEY (pickup_reservation_id),
    CONSTRAINT uk_pickup_reservations_acquire_operation
        UNIQUE (acquire_operation_id),
    CONSTRAINT fk_pickup_reservations_consumer
        FOREIGN KEY (consumer_account_id)
        REFERENCES consumer_accounts (consumer_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_pickup_reservations_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_pickup_reservations_store_name
        CHECK (CHAR_LENGTH(TRIM(store_name_snapshot)) BETWEEN 1 AND 100),
    CONSTRAINT ck_pickup_reservations_time_zone
        CHECK (CHAR_LENGTH(TRIM(time_zone_id_snapshot)) BETWEEN 1 AND 100),
    CONSTRAINT ck_pickup_reservations_status
        CHECK (status IN ('CONFIRMED', 'CANCELLED', 'PICKED_UP')),
    CONSTRAINT ck_pickup_reservations_cancel_actor
        CHECK (cancelled_by IS NULL OR cancelled_by IN ('CONSUMER', 'STORE_OPERATOR')),
    CONSTRAINT ck_pickup_reservations_cancel_reason
        CHECK (
            cancellation_reason IS NULL
            OR CHAR_LENGTH(TRIM(cancellation_reason)) BETWEEN 1 AND 500
        ),
    CONSTRAINT ck_pickup_reservations_terminal_state
        CHECK (
            (
                status = 'CONFIRMED'
                AND cancelled_by IS NULL
                AND cancellation_reason IS NULL
                AND cancelled_at IS NULL
                AND picked_up_at IS NULL
            )
            OR (
                status = 'CANCELLED'
                AND cancelled_by IS NOT NULL
                AND cancelled_at IS NOT NULL
                AND cancelled_at >= created_at
                AND picked_up_at IS NULL
                AND (
                    cancelled_by = 'CONSUMER'
                    OR cancellation_reason IS NOT NULL
                )
            )
            OR (
                status = 'PICKED_UP'
                AND cancelled_by IS NULL
                AND cancellation_reason IS NULL
                AND cancelled_at IS NULL
                AND picked_up_at IS NOT NULL
                AND picked_up_at >= created_at
            )
        ),
    INDEX idx_pickup_reservations_consumer (
        consumer_account_id,
        pickup_date,
        pickup_reservation_id
    ),
    INDEX idx_pickup_reservations_store (
        store_id,
        pickup_date,
        pickup_time,
        pickup_reservation_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE pickup_reservation_items (
    pickup_reservation_item_id BIGINT NOT NULL AUTO_INCREMENT,
    pickup_reservation_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    menu_inventory_bucket_id BIGINT NOT NULL,
    menu_policy_version BIGINT NOT NULL,
    menu_name_snapshot VARCHAR(100) NOT NULL,
    unit_price_snapshot INT NOT NULL,
    inventory_policy_version BIGINT NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (pickup_reservation_item_id),
    CONSTRAINT uk_pickup_reservation_items_reservation_bucket
        UNIQUE (pickup_reservation_id, menu_inventory_bucket_id),
    CONSTRAINT fk_pickup_reservation_items_reservation
        FOREIGN KEY (pickup_reservation_id)
        REFERENCES pickup_reservations (pickup_reservation_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_pickup_reservation_items_menu
        FOREIGN KEY (menu_id)
        REFERENCES menus (menu_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_pickup_reservation_items_bucket
        FOREIGN KEY (menu_inventory_bucket_id)
        REFERENCES menu_inventory_buckets (menu_inventory_bucket_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_pickup_reservation_items_versions
        CHECK (menu_policy_version > 0 AND inventory_policy_version > 0),
    CONSTRAINT ck_pickup_reservation_items_name
        CHECK (CHAR_LENGTH(TRIM(menu_name_snapshot)) BETWEEN 1 AND 100),
    CONSTRAINT ck_pickup_reservation_items_unit_price
        CHECK (unit_price_snapshot >= 0),
    CONSTRAINT ck_pickup_reservation_items_quantity
        CHECK (quantity > 0),
    INDEX idx_pickup_reservation_items_bucket (
        menu_inventory_bucket_id,
        pickup_reservation_item_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
