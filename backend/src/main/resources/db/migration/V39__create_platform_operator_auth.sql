CREATE TABLE platform_operator_accounts (
    platform_operator_account_id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    password_state VARCHAR(20) NOT NULL,
    temporary_password_expires_at DATETIME(6) NULL,
    temporary_password_failure_count INT NOT NULL DEFAULT 0,
    authority_version BIGINT NOT NULL DEFAULT 1,
    session_version BIGINT NOT NULL DEFAULT 1,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (platform_operator_account_id),
    CONSTRAINT uk_platform_operator_accounts_email UNIQUE (email),
    CONSTRAINT ck_platform_operator_accounts_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_platform_operator_accounts_password_state CHECK (password_state IN ('TEMPORARY', 'ACTIVE')),
    CONSTRAINT ck_platform_operator_accounts_temporary_failures CHECK (temporary_password_failure_count >= 0),
    CONSTRAINT ck_platform_operator_accounts_authority_version CHECK (authority_version >= 1),
    CONSTRAINT ck_platform_operator_accounts_session_version CHECK (session_version >= 1)
);

CREATE TABLE platform_operator_auth_events (
    platform_operator_auth_event_id BIGINT NOT NULL AUTO_INCREMENT,
    platform_operator_account_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    authority_version BIGINT NOT NULL,
    session_version BIGINT NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    event_key VARCHAR(100) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (platform_operator_auth_event_id),
    CONSTRAINT uk_platform_operator_auth_events_key UNIQUE (event_key),
    CONSTRAINT fk_platform_operator_auth_events_account FOREIGN KEY (platform_operator_account_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_platform_operator_auth_events_authority_version CHECK (authority_version >= 1),
    CONSTRAINT ck_platform_operator_auth_events_session_version CHECK (session_version >= 1),
    INDEX ix_platform_operator_auth_events_account_occurred (platform_operator_account_id, occurred_at)
);
