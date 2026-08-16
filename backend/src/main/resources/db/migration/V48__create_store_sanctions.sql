ALTER TABLE stores
    ADD COLUMN platform_management_allowed BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE platform_operator_audit_events
    DROP CHECK ck_platform_operator_audit_events_action,
    DROP CHECK ck_platform_operator_audit_events_reason;

ALTER TABLE platform_operator_audit_events
    ADD CONSTRAINT ck_platform_operator_audit_events_action CHECK (action IN (
        'LOGIN', 'REAUTHENTICATION', 'LOGOUT', 'REFRESH', 'INITIAL_PASSWORD_CHANGED',
        'SESSION_REVOKED', 'ACCOUNT_CREATED', 'AUTHORITY_REPLACED', 'ACCOUNT_SUSPENDED',
        'AUDIT_SEARCH', 'AUDIT_DETAIL_READ', 'AUDIT_CORRECTION',
        'STORE_CASE_CREATED', 'STORE_CASE_ASSIGNED', 'STORE_IMPACT_PREVIEWED',
        'STORE_SANCTION_CREATED', 'STORE_SANCTION_APPROVED', 'STORE_SANCTION_RELEASED'
    )),
    ADD CONSTRAINT ck_platform_operator_audit_events_reason CHECK (reason IN (
        'AUTHENTICATION_EVENT', 'ACCOUNT_PROVISIONING', 'RESPONSIBILITY_CHANGE',
        'EMPLOYMENT_END', 'SECURITY_RESPONSE', 'AUDIT_VERIFICATION', 'RECORD_CORRECTION',
        'STORE_ENFORCEMENT'
    ));

CREATE TABLE store_enforcement_states (
    store_enforcement_state_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    enforcement_version BIGINT NOT NULL DEFAULT 0,
    base_operation_status VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    base_reservation_enabled BOOLEAN NOT NULL,
    base_menu_hold_enabled BOOLEAN NOT NULL,
    base_pickup_enabled BOOLEAN NOT NULL,
    waiting_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    store_management_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    last_sanction_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (store_enforcement_state_id),
    CONSTRAINT uk_store_enforcement_states_store UNIQUE (store_id),
    CONSTRAINT fk_store_enforcement_states_store FOREIGN KEY (store_id)
        REFERENCES stores (store_id) ON DELETE RESTRICT,
    CONSTRAINT ck_store_enforcement_states_version CHECK (enforcement_version >= 0)
);

CREATE TABLE store_sanction_cases (
    store_sanction_case_id BIGINT NOT NULL AUTO_INCREMENT,
    case_public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    store_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    assigned_operator_id BIGINT NULL,
    violation_type VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_references JSON NOT NULL,
    policy_version VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_version BIGINT NOT NULL DEFAULT 1,
    submitted_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (store_sanction_case_id),
    CONSTRAINT uk_store_sanction_cases_public_id UNIQUE (case_public_id),
    CONSTRAINT fk_store_sanction_cases_store FOREIGN KEY (store_id)
        REFERENCES stores (store_id) ON DELETE RESTRICT,
    CONSTRAINT fk_store_sanction_cases_creator FOREIGN KEY (created_by)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT fk_store_sanction_cases_assignee FOREIGN KEY (assigned_operator_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_store_sanction_cases_status CHECK (status IN (
        'SUBMITTED', 'ASSIGNED', 'PENDING_APPROVAL', 'ACTIVE', 'RESOLVED', 'REJECTED'
    )),
    CONSTRAINT ck_store_sanction_cases_version CHECK (case_version >= 1),
    INDEX ix_store_sanction_cases_store_status (store_id, status, submitted_at)
);

CREATE TABLE store_sanction_impact_previews (
    store_sanction_impact_preview_id BIGINT NOT NULL AUTO_INCREMENT,
    case_public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    store_id BIGINT NOT NULL,
    case_version BIGINT NOT NULL,
    store_enforcement_version BIGINT NOT NULL,
    confirmed_reservation_count BIGINT NOT NULL,
    active_waiting_team_count BIGINT NOT NULL,
    confirmed_pickup_count BIGINT NOT NULL,
    unsettled_payment_count BIGINT NOT NULL,
    shape_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (store_sanction_impact_preview_id),
    CONSTRAINT fk_store_sanction_previews_case FOREIGN KEY (case_public_id)
        REFERENCES store_sanction_cases (case_public_id) ON DELETE RESTRICT,
    CONSTRAINT fk_store_sanction_previews_store FOREIGN KEY (store_id)
        REFERENCES stores (store_id) ON DELETE RESTRICT,
    CONSTRAINT ck_store_sanction_previews_counts CHECK (
        confirmed_reservation_count >= 0 AND active_waiting_team_count >= 0
        AND confirmed_pickup_count >= 0 AND unsettled_payment_count >= 0
    ),
    INDEX ix_store_sanction_previews_case_expiry (case_public_id, case_version, expires_at)
);

CREATE TABLE store_sanctions (
    store_sanction_id BIGINT NOT NULL AUTO_INCREMENT,
    case_public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    store_id BIGINT NOT NULL,
    sanction_type VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sanction_version BIGINT NOT NULL DEFAULT 1,
    store_enforcement_version BIGINT NOT NULL,
    restricted_features JSON NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    starts_at DATETIME(6) NULL,
    ends_at DATETIME(6) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    released_at DATETIME(6) NULL,
    active_store_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING_APPROVAL', 'ACTIVE') THEN 1 ELSE NULL END
    ) STORED,
    PRIMARY KEY (store_sanction_id),
    CONSTRAINT uk_store_sanctions_active UNIQUE (store_id, active_store_marker),
    CONSTRAINT fk_store_sanctions_case FOREIGN KEY (case_public_id)
        REFERENCES store_sanction_cases (case_public_id) ON DELETE RESTRICT,
    CONSTRAINT fk_store_sanctions_store FOREIGN KEY (store_id)
        REFERENCES stores (store_id) ON DELETE RESTRICT,
    CONSTRAINT fk_store_sanctions_creator FOREIGN KEY (created_by)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_store_sanctions_type CHECK (sanction_type IN (
        'WARNING', 'FEATURE_RESTRICTION', 'TEMPORARY_SUSPENSION', 'PERMANENT_EXIT'
    )),
    CONSTRAINT ck_store_sanctions_status CHECK (status IN (
        'PENDING_APPROVAL', 'ACTIVE', 'RELEASED', 'EXPIRED', 'REJECTED'
    )),
    CONSTRAINT ck_store_sanctions_versions CHECK (
        sanction_version >= 1 AND store_enforcement_version >= 0
    ),
    INDEX ix_store_sanctions_case (case_public_id, store_sanction_id),
    INDEX ix_store_sanctions_expiry (status, ends_at, store_sanction_id)
);

ALTER TABLE store_enforcement_states
    ADD CONSTRAINT fk_store_enforcement_states_last_sanction FOREIGN KEY (last_sanction_id)
        REFERENCES store_sanctions (store_sanction_id) ON DELETE RESTRICT;

CREATE TABLE store_sanction_approvals (
    store_sanction_approval_id BIGINT NOT NULL AUTO_INCREMENT,
    store_sanction_id BIGINT NOT NULL,
    approver_id BIGINT NOT NULL,
    note VARCHAR(500) NULL,
    approved_at DATETIME(6) NOT NULL,
    PRIMARY KEY (store_sanction_approval_id),
    CONSTRAINT uk_store_sanction_approver UNIQUE (store_sanction_id, approver_id),
    CONSTRAINT fk_store_sanction_approvals_sanction FOREIGN KEY (store_sanction_id)
        REFERENCES store_sanctions (store_sanction_id) ON DELETE RESTRICT,
    CONSTRAINT fk_store_sanction_approvals_operator FOREIGN KEY (approver_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT
);
