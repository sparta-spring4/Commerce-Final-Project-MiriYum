CREATE TABLE menu_holds (
    menu_hold_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    consumer_account_id BIGINT NOT NULL,
    service_date DATE NOT NULL,
    start_time TIME(6) NOT NULL,
    end_date DATE NOT NULL,
    end_time TIME(6) NOT NULL,
    acquire_operation_id VARCHAR(100) COLLATE utf8mb4_0900_as_cs NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (menu_hold_id),
    CONSTRAINT uk_menu_holds_reservation UNIQUE (reservation_id),
    CONSTRAINT uk_menu_holds_acquire_operation UNIQUE (acquire_operation_id),
    CONSTRAINT fk_menu_holds_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (reservation_id) ON DELETE RESTRICT,
    CONSTRAINT fk_menu_holds_store FOREIGN KEY (store_id) REFERENCES stores (store_id) ON DELETE RESTRICT,
    CONSTRAINT fk_menu_holds_consumer FOREIGN KEY (consumer_account_id) REFERENCES consumer_accounts (consumer_account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_menu_holds_service_interval CHECK (
        service_date < end_date OR (service_date = end_date AND start_time < end_time)
    ),
    CONSTRAINT ck_menu_holds_status CHECK (
        status IN ('CONFIRMED', 'RELEASED', 'FULFILLED')
    ),
    INDEX idx_menu_holds_store_service (store_id, service_date, start_time, menu_hold_id),
    INDEX idx_menu_holds_consumer (consumer_account_id, menu_hold_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE menu_hold_items (
    menu_hold_item_id BIGINT NOT NULL AUTO_INCREMENT,
    menu_hold_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    menu_inventory_bucket_id BIGINT NOT NULL,
    menu_policy_version BIGINT NOT NULL,
    inventory_policy_version BIGINT NOT NULL,
    quantity INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (menu_hold_item_id),
    CONSTRAINT uk_menu_hold_items_hold_bucket UNIQUE (menu_hold_id, menu_inventory_bucket_id),
    CONSTRAINT fk_menu_hold_items_hold FOREIGN KEY (menu_hold_id) REFERENCES menu_holds (menu_hold_id) ON DELETE RESTRICT,
    CONSTRAINT fk_menu_hold_items_menu FOREIGN KEY (menu_id) REFERENCES menus (menu_id) ON DELETE RESTRICT,
    CONSTRAINT fk_menu_hold_items_bucket FOREIGN KEY (menu_inventory_bucket_id) REFERENCES menu_inventory_buckets (menu_inventory_bucket_id) ON DELETE RESTRICT,
    CONSTRAINT ck_menu_hold_items_versions CHECK (
        menu_policy_version > 0 AND inventory_policy_version > 0
    ),
    CONSTRAINT ck_menu_hold_items_quantity CHECK (quantity > 0),
    INDEX idx_menu_hold_items_bucket (menu_inventory_bucket_id, menu_hold_item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
