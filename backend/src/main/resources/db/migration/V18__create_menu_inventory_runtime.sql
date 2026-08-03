CREATE TABLE menu_inventory_buckets (
    menu_inventory_bucket_id BIGINT NOT NULL AUTO_INCREMENT,
    menu_id BIGINT NOT NULL,
    service_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_date DATE NOT NULL,
    end_time TIME NOT NULL,
    time_zone_id VARCHAR(64) NOT NULL,
    inventory_policy_version BIGINT NOT NULL,
    total_supply INT NOT NULL,
    online_hold_capacity INT NOT NULL,
    online_hold_remaining INT NOT NULL,
    onsite_capacity INT NOT NULL,
    onsite_remaining INT NOT NULL,
    shared_capacity INT NOT NULL,
    shared_remaining INT NOT NULL,
    shared_online_allowed BOOLEAN NOT NULL,
    availability_status VARCHAR(20) NOT NULL,
    lock_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (menu_inventory_bucket_id),
    CONSTRAINT uk_menu_inventory_bucket_key UNIQUE (
        menu_id, service_date, start_time, end_date, end_time, inventory_policy_version
    ),
    CONSTRAINT fk_menu_inventory_bucket_menu
        FOREIGN KEY (menu_id) REFERENCES menus (menu_id) ON DELETE RESTRICT,
    CONSTRAINT ck_menu_inventory_interval CHECK (
        service_date < end_date OR (service_date = end_date AND start_time < end_time)
    ),
    CONSTRAINT ck_menu_inventory_policy_version CHECK (inventory_policy_version > 0),
    CONSTRAINT ck_menu_inventory_total_supply CHECK (total_supply >= 0),
    CONSTRAINT ck_menu_inventory_pool_nonnegative CHECK (
        online_hold_capacity >= 0 AND online_hold_remaining >= 0
        AND onsite_capacity >= 0 AND onsite_remaining >= 0
        AND shared_capacity >= 0 AND shared_remaining >= 0
    ),
    CONSTRAINT ck_menu_inventory_pool_remaining CHECK (
        online_hold_remaining <= online_hold_capacity
        AND onsite_remaining <= onsite_capacity
        AND shared_remaining <= shared_capacity
    ),
    CONSTRAINT ck_menu_inventory_pool_allocation CHECK (
        online_hold_capacity + onsite_capacity + shared_capacity <= total_supply
    ),
    CONSTRAINT ck_menu_inventory_availability
        CHECK (availability_status IN ('AVAILABLE', 'SOLD_OUT')),
    INDEX idx_menu_inventory_bucket_lookup (
        menu_id, service_date, start_time, end_date, end_time, inventory_policy_version
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE menu_inventory_ledger (
    menu_inventory_ledger_id BIGINT NOT NULL AUTO_INCREMENT,
    operation_id VARCHAR(100) NOT NULL,
    source_operation_id VARCHAR(100) NULL,
    menu_inventory_bucket_id BIGINT NOT NULL,
    operation_type VARCHAR(20) NOT NULL,
    pool_type VARCHAR(20) NOT NULL,
    quantity_delta INT NOT NULL,
    quantity_before INT NOT NULL,
    quantity_after INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (menu_inventory_ledger_id),
    CONSTRAINT uk_menu_inventory_ledger_operation_pool UNIQUE (
        operation_id, menu_inventory_bucket_id, operation_type, pool_type
    ),
    CONSTRAINT uk_menu_inventory_restore_source_pool UNIQUE (
        source_operation_id, menu_inventory_bucket_id, operation_type, pool_type
    ),
    CONSTRAINT fk_menu_inventory_ledger_bucket
        FOREIGN KEY (menu_inventory_bucket_id)
        REFERENCES menu_inventory_buckets (menu_inventory_bucket_id) ON DELETE RESTRICT,
    CONSTRAINT ck_menu_inventory_ledger_operation
        CHECK (operation_type IN ('ACQUIRE', 'RESTORE')),
    CONSTRAINT ck_menu_inventory_ledger_source CHECK (
        (operation_type = 'ACQUIRE' AND source_operation_id IS NULL)
        OR (operation_type = 'RESTORE' AND source_operation_id IS NOT NULL)
    ),
    CONSTRAINT ck_menu_inventory_ledger_pool
        CHECK (pool_type IN ('ONLINE_HOLD', 'SHARED')),
    CONSTRAINT ck_menu_inventory_ledger_quantity CHECK (
        quantity_delta <> 0 AND quantity_before >= 0 AND quantity_after >= 0
    ),
    INDEX idx_menu_inventory_ledger_bucket (
        menu_inventory_bucket_id, menu_inventory_ledger_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
