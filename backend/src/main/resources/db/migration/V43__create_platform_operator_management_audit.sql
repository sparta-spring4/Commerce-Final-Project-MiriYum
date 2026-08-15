ALTER TABLE admin_case_assignments
    ADD CONSTRAINT ck_admin_case_assignments_case_type CHECK (case_type IN (
        'ONBOARDING_REVIEW', 'MEMBER_SUPPORT', 'ACCOUNT_ENFORCEMENT',
        'STORE_ENFORCEMENT', 'OPERATIONS_MONITORING', 'PAYMENT_RECOVERY',
        'OPERATOR_MANAGEMENT', 'AUDIT_REVIEW', 'INCIDENT_RESPONSE'
    ));

ALTER TABLE platform_operator_reauthentication_approvals
    ADD CONSTRAINT ck_platform_operator_reauth_approvals_purpose CHECK (purpose IN (
        'ONBOARDING_DECISION', 'MEMBER_RECOVERY', 'ACCOUNT_SANCTION',
        'STORE_SANCTION', 'PAYMENT_RECOVERY', 'OPERATOR_CREATION',
        'OPERATOR_AUTHORITY_CHANGE', 'OPERATOR_SUSPENSION',
        'AUDIT_CORRECTION', 'INCIDENT_RESPONSE'
    )),
    ADD CONSTRAINT ck_platform_operator_reauth_approvals_target_type CHECK (target_type IN (
        'ONBOARDING_APPLICATION', 'CONSUMER_ACCOUNT', 'STORE_OPERATOR_ACCOUNT',
        'STORE', 'PAYMENT_RECOVERY_CASE', 'PLATFORM_OPERATOR_ACCOUNT',
        'AUDIT_EVENT', 'INCIDENT'
    ));

ALTER TABLE platform_operator_role_grants
    ADD COLUMN super_admin_singleton TINYINT
        GENERATED ALWAYS AS (CASE WHEN role = 'SUPER_ADMIN' THEN 1 ELSE NULL END) STORED,
    ADD CONSTRAINT uk_platform_operator_role_grants_single_super_admin
        UNIQUE (super_admin_singleton);

CREATE TABLE platform_operator_audit_events (
    platform_operator_audit_event_id BIGINT NOT NULL AUTO_INCREMENT,
    actor_platform_operator_account_id BIGINT NOT NULL,
    actor_authority_version BIGINT NOT NULL,
    actor_roles JSON NOT NULL,
    actor_permissions JSON NOT NULL,
    action VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outcome VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_type VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NULL,
    case_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    case_version BIGINT NULL,
    reauthentication_approval_id BIGINT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    before_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
    after_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
    before_roles JSON NOT NULL,
    after_roles JSON NOT NULL,
    before_permissions JSON NOT NULL,
    after_permissions JSON NOT NULL,
    corrected_action VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NULL,
    corrected_outcome VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
    corrected_target_type VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NULL,
    corrected_target_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    corrected_reason VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NULL,
    original_event_source VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
    original_event_id BIGINT NULL,
    correlation_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (platform_operator_audit_event_id),
    CONSTRAINT fk_platform_operator_audit_events_actor FOREIGN KEY (actor_platform_operator_account_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT uk_platform_operator_audit_events_original UNIQUE (original_event_source, original_event_id),
    CONSTRAINT ck_platform_operator_audit_events_authority_version CHECK (actor_authority_version >= 1),
    CONSTRAINT ck_platform_operator_audit_events_action CHECK (action IN (
        'LOGIN', 'REAUTHENTICATION', 'LOGOUT', 'REFRESH', 'INITIAL_PASSWORD_CHANGED',
        'SESSION_REVOKED', 'ACCOUNT_CREATED', 'AUTHORITY_REPLACED', 'ACCOUNT_SUSPENDED',
        'AUDIT_SEARCH', 'AUDIT_DETAIL_READ', 'AUDIT_CORRECTION'
    )),
    CONSTRAINT ck_platform_operator_audit_events_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED')),
    CONSTRAINT ck_platform_operator_audit_events_reason CHECK (reason IN (
        'AUTHENTICATION_EVENT', 'ACCOUNT_PROVISIONING', 'RESPONSIBILITY_CHANGE',
        'EMPLOYMENT_END', 'SECURITY_RESPONSE', 'AUDIT_VERIFICATION', 'RECORD_CORRECTION'
    )),
    CONSTRAINT ck_platform_operator_audit_events_case_version CHECK (case_version IS NULL OR case_version >= 1),
    CONSTRAINT ck_platform_operator_audit_events_original CHECK (
        (original_event_source IS NULL AND original_event_id IS NULL)
        OR (original_event_source IN ('AUTH', 'ADMIN') AND original_event_id >= 1)
    ),
    CONSTRAINT ck_platform_operator_audit_events_statuses CHECK (
        (before_status IS NULL OR before_status IN ('ACTIVE', 'SUSPENDED'))
        AND (after_status IS NULL OR after_status IN ('ACTIVE', 'SUSPENDED'))
    ),
    CONSTRAINT ck_platform_operator_audit_events_corrected_outcome CHECK (
        corrected_outcome IS NULL OR corrected_outcome IN ('SUCCESS', 'DENIED', 'FAILED')
    ),
    INDEX ix_platform_operator_audit_events_occurred (occurred_at, platform_operator_audit_event_id),
    INDEX ix_platform_operator_audit_events_actor_occurred
        (actor_platform_operator_account_id, occurred_at, platform_operator_audit_event_id),
    INDEX ix_platform_operator_audit_events_target_occurred
        (target_type, target_id, occurred_at, platform_operator_audit_event_id)
);

DELIMITER $$

CREATE TRIGGER trg_platform_operator_audit_events_no_update
BEFORE UPDATE ON platform_operator_audit_events
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'platform operator audit events are immutable';
END$$

CREATE TRIGGER trg_platform_operator_audit_events_no_delete
BEFORE DELETE ON platform_operator_audit_events
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'platform operator audit events are immutable';
END$$

DELIMITER ;
