CREATE TABLE menu_inventory_policy_audits (
    menu_inventory_policy_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    operator_account_id BIGINT NOT NULL,
    command_type VARCHAR(60) NOT NULL,
    idempotency_key CHAR(36) COLLATE utf8mb4_0900_as_cs NOT NULL,
    menu_inventory_bucket_id BIGINT NOT NULL,
    previous_menu_inventory_bucket_id BIGINT NULL,
    inventory_policy_version BIGINT NOT NULL,
    total_supply_before INT NOT NULL,
    total_supply_delta INT NOT NULL,
    total_supply_after INT NOT NULL,
    online_capacity_before INT NOT NULL,
    online_capacity_delta INT NOT NULL,
    online_capacity_after INT NOT NULL,
    onsite_capacity_before INT NOT NULL,
    onsite_capacity_delta INT NOT NULL,
    onsite_capacity_after INT NOT NULL,
    shared_capacity_before INT NOT NULL,
    shared_capacity_delta INT NOT NULL,
    shared_capacity_after INT NOT NULL,
    shared_online_allowed_before BOOLEAN NULL,
    shared_online_allowed_after BOOLEAN NOT NULL,
    availability_before VARCHAR(20) NULL,
    availability_after VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (menu_inventory_policy_audit_id),
    CONSTRAINT uk_menu_inventory_policy_audit_command UNIQUE (
        operator_account_id, command_type, idempotency_key
    ),
    CONSTRAINT fk_menu_inventory_policy_audit_bucket
        FOREIGN KEY (menu_inventory_bucket_id)
        REFERENCES menu_inventory_buckets (menu_inventory_bucket_id) ON DELETE RESTRICT,
    CONSTRAINT fk_menu_inventory_policy_audit_previous_bucket
        FOREIGN KEY (previous_menu_inventory_bucket_id)
        REFERENCES menu_inventory_buckets (menu_inventory_bucket_id) ON DELETE RESTRICT,
    CONSTRAINT ck_menu_inventory_policy_audit_version
        CHECK (inventory_policy_version > 0),
    CONSTRAINT ck_menu_inventory_policy_audit_quantities CHECK (
        total_supply_before >= 0
        AND total_supply_after = total_supply_before + total_supply_delta
        AND online_capacity_before >= 0
        AND online_capacity_after = online_capacity_before + online_capacity_delta
        AND onsite_capacity_before >= 0
        AND onsite_capacity_after = onsite_capacity_before + onsite_capacity_delta
        AND shared_capacity_before >= 0
        AND shared_capacity_after = shared_capacity_before + shared_capacity_delta
    ),
    CONSTRAINT ck_menu_inventory_policy_audit_availability_before CHECK (
        availability_before IS NULL OR availability_before IN ('AVAILABLE', 'SOLD_OUT')
    ),
    CONSTRAINT ck_menu_inventory_policy_audit_availability_after
        CHECK (availability_after IN ('AVAILABLE', 'SOLD_OUT')),
    INDEX idx_menu_inventory_policy_audit_bucket (
        menu_inventory_bucket_id, menu_inventory_policy_audit_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
