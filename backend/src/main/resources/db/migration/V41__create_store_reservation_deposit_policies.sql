CREATE TABLE store_reservation_deposit_policies (
    store_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    rate_percent INT NOT NULL,
    policy_version BIGINT NOT NULL,
    lock_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (store_id),
    CONSTRAINT fk_store_reservation_deposit_policy_store
        FOREIGN KEY (store_id) REFERENCES stores (store_id) ON DELETE RESTRICT,
    CONSTRAINT ck_store_reservation_deposit_policy_enabled
        CHECK (enabled IN (FALSE, TRUE)),
    CONSTRAINT ck_store_reservation_deposit_policy_rate
        CHECK (rate_percent BETWEEN 10 AND 30),
    CONSTRAINT ck_store_reservation_deposit_policy_version
        CHECK (policy_version > 0),
    CONSTRAINT ck_store_reservation_deposit_policy_lock_version
        CHECK (lock_version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
