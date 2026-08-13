-- Kakao OAuth accounts are created without a password hash.
ALTER TABLE consumer_accounts MODIFY password_hash VARCHAR(255) NULL;
ALTER TABLE store_operator_accounts MODIFY password_hash VARCHAR(255) NULL;

CREATE TABLE social_login_links (
    social_login_link_id BIGINT NOT NULL AUTO_INCREMENT,
    namespace VARCHAR(30) NOT NULL,
    account_id BIGINT NOT NULL,
    provider VARCHAR(30) NOT NULL,
    fingerprint_key_version VARCHAR(30) NOT NULL,
    provider_subject_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (social_login_link_id),
    CONSTRAINT uk_social_login_links_namespace_provider_subject
        UNIQUE (namespace, provider, provider_subject_fingerprint),
    CONSTRAINT uk_social_login_links_namespace_account_provider
        UNIQUE (namespace, account_id, provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
