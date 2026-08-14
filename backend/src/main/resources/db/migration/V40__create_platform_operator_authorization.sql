CREATE TABLE platform_operator_role_grants (
    platform_operator_role_grant_id BIGINT NOT NULL AUTO_INCREMENT,
    platform_operator_account_id BIGINT NOT NULL,
    role VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    granted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (platform_operator_role_grant_id),
    CONSTRAINT uk_platform_operator_role_grants_account_role
        UNIQUE (platform_operator_account_id, role),
    CONSTRAINT fk_platform_operator_role_grants_account FOREIGN KEY (platform_operator_account_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_platform_operator_role_grants_role CHECK (role IN (
        'SUPER_ADMIN', 'ONBOARDING_REVIEWER', 'MEMBER_SUPPORT_OPERATOR',
        'ENFORCEMENT_OPERATOR', 'PAYMENT_RECOVERY_OPERATOR', 'OPERATIONS_MONITOR',
        'AUDIT_READER', 'INCIDENT_RESPONDER'
    )),
    INDEX ix_platform_operator_role_grants_account (platform_operator_account_id)
);

CREATE TABLE platform_operator_permission_grants (
    platform_operator_permission_grant_id BIGINT NOT NULL AUTO_INCREMENT,
    platform_operator_account_id BIGINT NOT NULL,
    permission VARCHAR(70) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    granted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (platform_operator_permission_grant_id),
    CONSTRAINT uk_platform_operator_permission_grants_account_permission
        UNIQUE (platform_operator_account_id, permission),
    CONSTRAINT fk_platform_operator_permission_grants_account FOREIGN KEY (platform_operator_account_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_platform_operator_permission_grants_permission CHECK (permission IN (
        'OPERATOR_CREATE', 'OPERATOR_AUTHORITY_MANAGE', 'OPERATOR_SUSPEND',
        'ONBOARDING_REVIEW', 'ONBOARDING_EVIDENCE_READ', 'MEMBER_READ_MINIMAL',
        'MEMBER_RECOVERY', 'ACCOUNT_SANCTION', 'ACCOUNT_APPEAL_REVIEW',
        'STORE_READ_MINIMAL', 'STORE_SANCTION', 'OPERATIONS_MONITOR_READ',
        'PAYMENT_RECOVERY_EXECUTE', 'PAYMENT_RECOVERY_HIGH_VALUE_APPROVE',
        'AUDIT_READ', 'INCIDENT_RESPOND', 'BREAK_GLASS_APPROVE'
    )),
    INDEX ix_platform_operator_permission_grants_account (platform_operator_account_id)
);

CREATE TABLE admin_case_assignments (
    admin_case_assignment_id BIGINT NOT NULL AUTO_INCREMENT,
    case_type VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_version BIGINT NOT NULL,
    platform_operator_account_id BIGINT NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (admin_case_assignment_id),
    CONSTRAINT uk_admin_case_assignments_case_version
        UNIQUE (case_type, case_id, case_version),
    CONSTRAINT fk_admin_case_assignments_account FOREIGN KEY (platform_operator_account_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_admin_case_assignments_case_version CHECK (case_version >= 1),
    CONSTRAINT ck_admin_case_assignments_status CHECK (status IN ('ASSIGNED', 'REASSIGNED', 'CLOSED')),
    INDEX ix_admin_case_assignments_lookup
        (case_type, case_id, case_version, platform_operator_account_id, status, expires_at)
);

CREATE TABLE platform_operator_reauthentication_approvals (
    platform_operator_reauthentication_approval_id BIGINT NOT NULL AUTO_INCREMENT,
    approval_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    platform_operator_account_id BIGINT NOT NULL,
    purpose VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    authority_version BIGINT NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    PRIMARY KEY (platform_operator_reauthentication_approval_id),
    CONSTRAINT uk_platform_operator_reauth_approvals_digest UNIQUE (approval_digest),
    CONSTRAINT fk_platform_operator_reauth_approvals_account FOREIGN KEY (platform_operator_account_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_platform_operator_reauth_approvals_authority_version CHECK (authority_version >= 1),
    CONSTRAINT ck_platform_operator_reauth_approvals_expiry CHECK (expires_at > issued_at),
    INDEX ix_platform_operator_reauth_approvals_account_expiry
        (platform_operator_account_id, expires_at)
);

CREATE TABLE platform_operator_authority_guard (
    guard_id TINYINT NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (guard_id),
    CONSTRAINT ck_platform_operator_authority_guard_singleton CHECK (guard_id = 1)
);

INSERT INTO platform_operator_authority_guard (guard_id, row_version) VALUES (1, 0);
