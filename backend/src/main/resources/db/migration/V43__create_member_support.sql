ALTER TABLE consumer_accounts
    ADD COLUMN password_reset_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN support_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_consumer_accounts_support_version CHECK (support_version >= 0);

ALTER TABLE store_operator_accounts
    ADD COLUMN password_reset_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN support_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_store_operator_accounts_support_version CHECK (support_version >= 0);

ALTER TABLE platform_operator_permission_grants
    DROP CHECK ck_platform_operator_permission_grants_permission;

ALTER TABLE platform_operator_permission_grants
    ADD CONSTRAINT ck_platform_operator_permission_grants_permission CHECK (permission IN (
        'OPERATOR_CREATE', 'OPERATOR_AUTHORITY_MANAGE', 'OPERATOR_SUSPEND',
        'ONBOARDING_REVIEW', 'ONBOARDING_EVIDENCE_READ', 'MEMBER_READ_MINIMAL',
        'MEMBER_RECOVERY', 'ACCOUNT_SANCTION', 'ACCOUNT_PERMANENT_SANCTION_APPROVE',
        'ACCOUNT_APPEAL_REVIEW', 'STORE_READ_MINIMAL', 'STORE_SANCTION',
        'OPERATIONS_MONITOR_READ', 'PAYMENT_RECOVERY_EXECUTE',
        'PAYMENT_RECOVERY_HIGH_VALUE_APPROVE', 'AUDIT_READ', 'INCIDENT_RESPOND',
        'BREAK_GLASS_APPROVE'
    ));

CREATE TABLE member_identity_verifications (
    member_identity_verification_id BIGINT NOT NULL AUTO_INCREMENT,
    proof_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_type VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id BIGINT NOT NULL,
    purpose VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    encrypted_new_email VARBINARY(1024) NULL,
    new_email_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_sanction_id BIGINT NULL,
    evidence_verified BOOLEAN NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (member_identity_verification_id),
    CONSTRAINT uk_member_identity_verifications_digest UNIQUE (proof_digest),
    CONSTRAINT ck_member_identity_verifications_account_type
        CHECK (account_type IN ('CONSUMER', 'STORE_OPERATOR')),
    CONSTRAINT ck_member_identity_verifications_purpose
        CHECK (purpose IN ('MEMBER_RECOVERY', 'ACCOUNT_APPEAL', 'PASSWORD_RESET')),
    CONSTRAINT ck_member_identity_verifications_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_member_identity_verifications_recovery_email CHECK (
        purpose <> 'MEMBER_RECOVERY'
        OR (encrypted_new_email IS NOT NULL AND new_email_digest IS NOT NULL)
    ),
    CONSTRAINT ck_member_identity_verifications_appeal_source CHECK (
        purpose <> 'ACCOUNT_APPEAL' OR source_sanction_id IS NOT NULL
    ),
    INDEX ix_member_identity_verifications_consume
        (proof_digest, purpose, account_type, expires_at, consumed_at)
);

CREATE TABLE member_support_cases (
    member_support_case_id BIGINT NOT NULL AUTO_INCREMENT,
    case_public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_type VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_type VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id BIGINT NOT NULL,
    identity_verification_id BIGINT NULL,
    source_sanction_id BIGINT NULL,
    password_reset_verification_id BIGINT NULL,
    password_reset_completed_at DATETIME(6) NULL,
    status VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_support_version BIGINT NOT NULL,
    reason_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    decision_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    row_version BIGINT NOT NULL DEFAULT 1,
    submitted_at DATETIME(6) NOT NULL,
    decided_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    active_case_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('SUBMITTED', 'ASSIGNED', 'PENDING_ADDITIONAL_APPROVAL')
             THEN 1 ELSE NULL END
    ) STORED,
    PRIMARY KEY (member_support_case_id),
    CONSTRAINT uk_member_support_cases_public_id UNIQUE (case_public_id),
    CONSTRAINT uk_member_support_cases_active
        UNIQUE (case_type, account_type, account_id, active_case_marker),
    CONSTRAINT fk_member_support_cases_identity_verification
        FOREIGN KEY (identity_verification_id)
        REFERENCES member_identity_verifications (member_identity_verification_id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_support_cases_password_reset_verification
        FOREIGN KEY (password_reset_verification_id)
        REFERENCES member_identity_verifications (member_identity_verification_id) ON DELETE RESTRICT,
    CONSTRAINT ck_member_support_cases_type
        CHECK (case_type IN ('ACCOUNT_RECOVERY', 'ACCOUNT_SANCTION', 'ACCOUNT_APPEAL')),
    CONSTRAINT ck_member_support_cases_account_type
        CHECK (account_type IN ('CONSUMER', 'STORE_OPERATOR')),
    CONSTRAINT ck_member_support_cases_status CHECK (status IN (
        'SUBMITTED', 'ASSIGNED', 'PENDING_ADDITIONAL_APPROVAL',
        'APPROVED', 'REJECTED', 'UPHELD', 'REDUCED', 'CANCELLED'
    )),
    CONSTRAINT ck_member_support_cases_target_version CHECK (target_support_version >= 0),
    CONSTRAINT ck_member_support_cases_row_version CHECK (row_version >= 1),
    INDEX ix_member_support_cases_queue (case_type, status, submitted_at, member_support_case_id),
    INDEX ix_member_support_cases_target (account_type, account_id, submitted_at)
);

CREATE TABLE member_sanctions (
    member_sanction_id BIGINT NOT NULL AUTO_INCREMENT,
    sanction_public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    member_support_case_id BIGINT NOT NULL,
    previous_sanction_id BIGINT NULL,
    account_type VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id BIGINT NOT NULL,
    level VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    restricted_features JSON NOT NULL,
    reason_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_version VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proposed_by_operator_id BIGINT NOT NULL,
    applied_by_operator_id BIGINT NULL,
    proposed_at DATETIME(6) NOT NULL,
    applied_at DATETIME(6) NULL,
    ends_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    active_level_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING_ADDITIONAL_APPROVAL', 'APPLIED')
             THEN 1 ELSE NULL END
    ) STORED,
    PRIMARY KEY (member_sanction_id),
    CONSTRAINT uk_member_sanctions_public_id UNIQUE (sanction_public_id),
    CONSTRAINT uk_member_sanctions_case UNIQUE (member_support_case_id),
    CONSTRAINT uk_member_sanctions_active_level
        UNIQUE (account_type, account_id, level, active_level_marker),
    CONSTRAINT fk_member_sanctions_case FOREIGN KEY (member_support_case_id)
        REFERENCES member_support_cases (member_support_case_id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_sanctions_previous FOREIGN KEY (previous_sanction_id)
        REFERENCES member_sanctions (member_sanction_id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_sanctions_proposer FOREIGN KEY (proposed_by_operator_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_sanctions_applier FOREIGN KEY (applied_by_operator_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_member_sanctions_account_type
        CHECK (account_type IN ('CONSUMER', 'STORE_OPERATOR')),
    CONSTRAINT ck_member_sanctions_level CHECK (level IN (
        'WARNING', 'FEATURE_RESTRICTION', 'TEMPORARY_SUSPENSION', 'PERMANENT_SUSPENSION'
    )),
    CONSTRAINT ck_member_sanctions_status CHECK (status IN (
        'PENDING_ADDITIONAL_APPROVAL', 'APPLIED', 'EXPIRED', 'REDUCED', 'CANCELLED'
    )),
    CONSTRAINT ck_member_sanctions_row_version CHECK (row_version >= 1),
    INDEX ix_member_sanctions_active (account_type, account_id, status, ends_at),
    INDEX ix_member_sanctions_expiry (status, ends_at, member_sanction_id)
);

ALTER TABLE member_support_cases
    ADD CONSTRAINT fk_member_support_cases_source_sanction
        FOREIGN KEY (source_sanction_id)
        REFERENCES member_sanctions (member_sanction_id) ON DELETE RESTRICT;

ALTER TABLE member_identity_verifications
    ADD CONSTRAINT fk_member_identity_verifications_source_sanction
        FOREIGN KEY (source_sanction_id)
        REFERENCES member_sanctions (member_sanction_id) ON DELETE RESTRICT;

CREATE TABLE member_sanction_approvals (
    member_sanction_approval_id BIGINT NOT NULL AUTO_INCREMENT,
    member_sanction_id BIGINT NOT NULL,
    proposer_operator_id BIGINT NOT NULL,
    approver_operator_id BIGINT NOT NULL,
    reason_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approved_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (member_sanction_approval_id),
    CONSTRAINT uk_member_sanction_approvals_sanction UNIQUE (member_sanction_id),
    CONSTRAINT fk_member_sanction_approvals_sanction FOREIGN KEY (member_sanction_id)
        REFERENCES member_sanctions (member_sanction_id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_sanction_approvals_proposer FOREIGN KEY (proposer_operator_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_sanction_approvals_approver FOREIGN KEY (approver_operator_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_member_sanction_approvals_separation
        CHECK (proposer_operator_id <> approver_operator_id)
);

CREATE TABLE member_support_audits (
    member_support_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    correlation_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id BIGINT NOT NULL,
    roles_snapshot JSON NOT NULL,
    permissions_snapshot JSON NOT NULL,
    authority_version BIGINT NOT NULL,
    case_type VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_version BIGINT NOT NULL,
    purpose VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_version VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NULL,
    occurred_at DATETIME(6) NOT NULL,
    retention_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (member_support_audit_id),
    CONSTRAINT uk_member_support_audits_correlation_decision
        UNIQUE (correlation_id, decision_code),
    CONSTRAINT fk_member_support_audits_operator FOREIGN KEY (operator_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_member_support_audits_authority_version CHECK (authority_version >= 1),
    CONSTRAINT ck_member_support_audits_case_version CHECK (case_version >= 1),
    INDEX ix_member_support_audits_case (case_type, case_id, occurred_at),
    INDEX ix_member_support_audits_retention (retention_until, member_support_audit_id)
);
