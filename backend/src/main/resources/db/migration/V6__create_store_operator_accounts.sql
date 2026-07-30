CREATE TABLE store_operator_accounts (
    store_operator_account_id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    -- 해시 방식 접두사({sha256-bcrypt} 등)와 해시를 함께 담는다. BCrypt 해시 60자에 접두사가
    -- 붙으면 72자를 넘고, 나중에 다른 방식으로 바꿀 여지도 남겨야 하므로 넉넉히 잡는다.
    password_hash VARCHAR(255) NOT NULL,
    phone VARCHAR(512) NULL,
    display_name VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (store_operator_account_id),
    UNIQUE KEY uk_store_operator_accounts_email (email),
    UNIQUE KEY uk_store_operator_accounts_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
