CREATE TABLE stores (
    store_id BIGINT NOT NULL AUTO_INCREMENT,
    store_operator_account_id BIGINT NOT NULL,
    business_registration_number VARCHAR(10) NOT NULL,
    business_type VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    region VARCHAR(20) NOT NULL,
    address VARCHAR(300) NOT NULL,
    store_category_code VARCHAR(50) COLLATE utf8mb4_0900_as_cs NOT NULL,
    verification_status VARCHAR(20) NOT NULL,
    operation_status VARCHAR(30) NOT NULL,
    pickup_eligibility VARCHAR(20) NOT NULL,
    reservation_enabled BOOLEAN NOT NULL,
    menu_hold_enabled BOOLEAN NOT NULL,
    pickup_enabled BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (store_id),
    CONSTRAINT uk_stores_active_business_number
        UNIQUE (business_registration_number),
    CONSTRAINT fk_stores_operator
        FOREIGN KEY (store_operator_account_id)
        REFERENCES store_operator_accounts (store_operator_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_stores_category
        FOREIGN KEY (store_category_code)
        REFERENCES store_category (code)
        ON DELETE RESTRICT,
    CONSTRAINT ck_stores_business_number
        CHECK (REGEXP_LIKE(business_registration_number, '^[0-9]{10}$', 'c')),
    CONSTRAINT ck_stores_business_type
        CHECK (business_type IN ('CAFE', 'BAKERY', 'OTHER')),
    CONSTRAINT ck_stores_region
        CHECK (region IN ('SEOUL', 'BUSAN', 'DAEGU', 'DAEJEON', 'GWANGJU')),
    CONSTRAINT ck_stores_verification_status
        CHECK (verification_status = 'APPROVED'),
    CONSTRAINT ck_stores_operation_status
        CHECK (operation_status IN ('OPEN', 'TEMPORARILY_CLOSED', 'CLOSED')),
    CONSTRAINT ck_stores_pickup_eligibility
        CHECK (
            (
                business_type IN ('CAFE', 'BAKERY')
                AND pickup_eligibility = 'ELIGIBLE'
            )
            OR (
                business_type = 'OTHER'
                AND pickup_eligibility = 'INELIGIBLE'
                AND pickup_enabled = FALSE
            )
        ),
    INDEX idx_stores_operator (store_operator_account_id),
    INDEX idx_stores_region_category (region, store_category_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE store_tag_assignment (
    store_id BIGINT NOT NULL,
    tag_code VARCHAR(50) COLLATE utf8mb4_0900_as_cs NOT NULL,
    PRIMARY KEY (store_id, tag_code),
    CONSTRAINT fk_store_tag_assignment_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_store_tag_assignment_tag
        FOREIGN KEY (tag_code)
        REFERENCES store_tag (code)
        ON DELETE RESTRICT,
    INDEX idx_store_tag_assignment_tag (tag_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
