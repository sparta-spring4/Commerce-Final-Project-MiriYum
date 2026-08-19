-- Issue #281: Platform Operator payment/refund manual recovery workflow.

ALTER TABLE platform_operator_audit_events
    DROP CHECK ck_platform_operator_audit_events_action,
    DROP CHECK ck_platform_operator_audit_events_reason;

ALTER TABLE platform_operator_audit_events
    ADD CONSTRAINT ck_platform_operator_audit_events_action CHECK (action IN (
        'LOGIN', 'REAUTHENTICATION', 'LOGOUT', 'REFRESH', 'INITIAL_PASSWORD_CHANGED',
        'SESSION_REVOKED', 'ACCOUNT_CREATED', 'AUTHORITY_REPLACED', 'ACCOUNT_SUSPENDED',
        'AUDIT_SEARCH', 'AUDIT_DETAIL_READ', 'AUDIT_CORRECTION',
        'STORE_SEARCHED', 'STORE_DETAIL_READ', 'STORE_CASE_CREATED', 'STORE_CASE_ASSIGNED',
        'STORE_CASE_DETAIL_READ', 'STORE_IMPACT_PREVIEWED', 'STORE_SANCTION_PROPOSED',
        'STORE_SANCTION_APPROVED', 'STORE_SANCTION_APPLIED', 'STORE_SANCTION_RELEASED',
        'STORE_SANCTION_EXPIRED', 'STORE_SANCTION_REJECTED',
        'PAYMENT_RECOVERY_CASE_CREATED', 'PAYMENT_RECOVERY_CASE_ASSIGNED',
        'PAYMENT_RECOVERY_REQUERY_REQUESTED', 'PAYMENT_RECOVERY_PROPOSED',
        'PAYMENT_RECOVERY_APPROVED', 'PAYMENT_RECOVERY_EXECUTION_QUEUED',
        'PAYMENT_RECOVERY_EXECUTED', 'PAYMENT_RECOVERY_VERIFIED',
        'PAYMENT_RECOVERY_HELD', 'PAYMENT_RECOVERY_CLOSED'
    )),
    ADD CONSTRAINT ck_platform_operator_audit_events_reason CHECK (reason IN (
        'AUTHENTICATION_EVENT', 'ACCOUNT_PROVISIONING', 'RESPONSIBILITY_CHANGE',
        'EMPLOYMENT_END', 'SECURITY_RESPONSE', 'AUDIT_VERIFICATION', 'RECORD_CORRECTION',
        'STORE_ENFORCEMENT', 'PAYMENT_RECOVERY'
    ));

CREATE TABLE payment_recovery_cases (
    payment_recovery_case_id BIGINT NOT NULL AUTO_INCREMENT,
    case_public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    handoff_id VARCHAR(19) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lineage_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_sequence BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_version BIGINT NOT NULL DEFAULT 1,
    current_proposal_version BIGINT NOT NULL DEFAULT 0,
    recovery_kind VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_amount_minor BIGINT NOT NULL,
    cumulative_refunded_amount_minor BIGINT NOT NULL,
    remaining_refundable_amount_minor BIGINT NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    masked_provider_reference VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
    allowed_actions JSON NOT NULL,
    handoff_version BIGINT NOT NULL,
    payment_version BIGINT NOT NULL,
    recovery_version BIGINT NOT NULL,
    active_lineage_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            CASE WHEN status NOT IN ('COMPLETED', 'FAILED_UNRESOLVED') THEN lineage_id ELSE NULL END
        ) STORED,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (payment_recovery_case_id),
    CONSTRAINT uk_payment_recovery_cases_public_id UNIQUE (case_public_id),
    CONSTRAINT uk_payment_recovery_cases_handoff UNIQUE (handoff_id),
    CONSTRAINT uk_payment_recovery_cases_lineage_sequence UNIQUE (lineage_id, case_sequence),
    CONSTRAINT uk_payment_recovery_cases_active_lineage UNIQUE (active_lineage_key),
    CONSTRAINT ck_payment_recovery_cases_status CHECK (status IN (
        'RECONCILIATION_PENDING', 'INVESTIGATING', 'PROPOSED',
        'ADDITIONAL_APPROVAL_PENDING', 'EXECUTING', 'VERIFYING',
        'COMPLETED', 'HOLD', 'FAILED', 'FAILED_UNRESOLVED'
    )),
    CONSTRAINT ck_payment_recovery_cases_kind CHECK (recovery_kind IN (
        'DISPOSITION_RESULT_UNKNOWN', 'DISPOSITION_FAILED',
        'REFUND_RESULT_UNKNOWN', 'REFUND_FAILED'
    )),
    CONSTRAINT ck_payment_recovery_cases_result CHECK (result_status IN (
        'UNKNOWN', 'FAILED', 'SUCCEEDED'
    )),
    CONSTRAINT ck_payment_recovery_cases_versions CHECK (
        case_version >= 1 AND current_proposal_version >= 0
        AND handoff_version >= 0 AND payment_version >= 0 AND recovery_version >= 0
        AND row_version >= 0
    ),
    CONSTRAINT ck_payment_recovery_cases_amounts CHECK (
        original_amount_minor > 0 AND cumulative_refunded_amount_minor >= 0
        AND remaining_refundable_amount_minor >= 0
        AND cumulative_refunded_amount_minor + remaining_refundable_amount_minor
            <= original_amount_minor
    ),
    CONSTRAINT ck_payment_recovery_cases_sequence CHECK (case_sequence >= 1),
    INDEX ix_payment_recovery_cases_status_updated (status, updated_at, payment_recovery_case_id)
);

CREATE TABLE payment_recovery_proposals (
    payment_recovery_proposal_id BIGINT NOT NULL AUTO_INCREMENT,
    case_public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proposal_version BIGINT NOT NULL,
    action VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_amount_minor BIGINT NOT NULL,
    cumulative_lineage_amount_minor BIGINT NOT NULL,
    original_amount_minor BIGINT NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_tier VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_handoff_version BIGINT NOT NULL,
    expected_payment_version BIGINT NOT NULL,
    expected_recovery_version BIGINT NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requester_platform_operator_account_id BIGINT NOT NULL,
    requester_authority_version BIGINT NOT NULL,
    requester_roles JSON NOT NULL,
    requester_permissions JSON NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (payment_recovery_proposal_id),
    CONSTRAINT uk_payment_recovery_proposals_version UNIQUE (case_public_id, proposal_version),
    CONSTRAINT uk_payment_recovery_proposals_fingerprint UNIQUE (case_public_id, request_fingerprint),
    CONSTRAINT fk_payment_recovery_proposals_case FOREIGN KEY (case_public_id)
        REFERENCES payment_recovery_cases (case_public_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_recovery_proposals_requester FOREIGN KEY (requester_platform_operator_account_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_payment_recovery_proposals_action CHECK (action = 'RETRY_REFUND'),
    CONSTRAINT ck_payment_recovery_proposals_tier CHECK (approval_tier IN (
        'SINGLE_OPERATOR', 'ADDITIONAL_SUPER_ADMIN'
    )),
    CONSTRAINT ck_payment_recovery_proposals_versions CHECK (
        proposal_version >= 1 AND expected_handoff_version >= 0
        AND expected_payment_version >= 0 AND expected_recovery_version >= 0
        AND requester_authority_version >= 1
    ),
    CONSTRAINT ck_payment_recovery_proposals_amounts CHECK (
        requested_amount_minor > 0 AND cumulative_lineage_amount_minor > 0
        AND original_amount_minor > 0
    )
);

CREATE TABLE payment_recovery_approvals (
    payment_recovery_approval_id BIGINT NOT NULL AUTO_INCREMENT,
    case_public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proposal_version BIGINT NOT NULL,
    approval_tier VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requester_platform_operator_account_id BIGINT NOT NULL,
    approver_platform_operator_account_id BIGINT NOT NULL,
    approver_authority_version BIGINT NOT NULL,
    approver_roles JSON NOT NULL,
    approver_permissions JSON NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approved_at DATETIME(6) NOT NULL,
    PRIMARY KEY (payment_recovery_approval_id),
    CONSTRAINT uk_payment_recovery_approvals_actor
        UNIQUE (case_public_id, proposal_version, approver_platform_operator_account_id),
    CONSTRAINT uk_payment_recovery_approvals_proposal UNIQUE (case_public_id, proposal_version),
    CONSTRAINT fk_payment_recovery_approvals_proposal FOREIGN KEY (case_public_id, proposal_version)
        REFERENCES payment_recovery_proposals (case_public_id, proposal_version) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_recovery_approvals_requester FOREIGN KEY (requester_platform_operator_account_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_recovery_approvals_approver FOREIGN KEY (approver_platform_operator_account_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_payment_recovery_approvals_tier CHECK (approval_tier IN (
        'SINGLE_OPERATOR', 'ADDITIONAL_SUPER_ADMIN'
    )),
    CONSTRAINT ck_payment_recovery_approvals_actor_separation CHECK (
        (approval_tier = 'SINGLE_OPERATOR'
            AND requester_platform_operator_account_id = approver_platform_operator_account_id)
        OR (approval_tier = 'ADDITIONAL_SUPER_ADMIN'
            AND requester_platform_operator_account_id <> approver_platform_operator_account_id)
    ),
    CONSTRAINT ck_payment_recovery_approvals_versions CHECK (
        proposal_version >= 1 AND approver_authority_version >= 1
    )
);

CREATE TABLE payment_recovery_executions (
    payment_recovery_execution_id BIGINT NOT NULL AUTO_INCREMENT,
    case_public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proposal_version BIGINT NULL,
    execution_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    authorized_case_version BIGINT NOT NULL,
    expected_handoff_version BIGINT NOT NULL,
    expected_payment_version BIGINT NOT NULL,
    expected_recovery_version BIGINT NOT NULL,
    requester_platform_operator_account_id BIGINT NOT NULL,
    requester_authority_version BIGINT NOT NULL,
    approver_platform_operator_account_id BIGINT NOT NULL,
    approver_authority_version BIGINT NOT NULL,
    lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_token BIGINT NOT NULL DEFAULT 0,
    lease_expires_at DATETIME(6) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    lookup_attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    masked_outcome VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (payment_recovery_execution_id),
    CONSTRAINT uk_payment_recovery_executions_key UNIQUE (execution_key),
    CONSTRAINT uk_payment_recovery_executions_proposal_operation
        UNIQUE (case_public_id, proposal_version, operation),
    CONSTRAINT fk_payment_recovery_executions_case FOREIGN KEY (case_public_id)
        REFERENCES payment_recovery_cases (case_public_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_recovery_executions_requester FOREIGN KEY (requester_platform_operator_account_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_recovery_executions_approver FOREIGN KEY (approver_platform_operator_account_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_payment_recovery_executions_operation CHECK (operation IN (
        'REQUERY_PROVIDER_RESULT', 'RETRY_REFUND'
    )),
    CONSTRAINT ck_payment_recovery_executions_status CHECK (status IN (
        'PENDING', 'PROCESSING', 'VERIFYING', 'SUCCEEDED', 'FAILED', 'HOLD'
    )),
    CONSTRAINT ck_payment_recovery_executions_versions CHECK (
        (proposal_version IS NULL OR proposal_version >= 1)
        AND authorized_case_version >= 1 AND expected_handoff_version >= 0
        AND expected_payment_version >= 0 AND expected_recovery_version >= 0
        AND requester_authority_version >= 1 AND approver_authority_version >= 1
        AND lease_token >= 0 AND attempt_count >= 0 AND lookup_attempt_count >= 0
        AND row_version >= 0
    ),
    CONSTRAINT ck_payment_recovery_executions_lease CHECK (
        (lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL AND lease_token >= 1)
    ),
    CONSTRAINT ck_payment_recovery_executions_proposal CHECK (
        operation = 'REQUERY_PROVIDER_RESULT'
        OR (operation = 'RETRY_REFUND' AND proposal_version IS NOT NULL)
    ),
    INDEX ix_payment_recovery_executions_due
        (status, next_attempt_at, lease_expires_at, payment_recovery_execution_id)
);

DELIMITER $$

CREATE TRIGGER trg_payment_recovery_proposals_no_update
BEFORE UPDATE ON payment_recovery_proposals
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'payment recovery proposals are immutable';
END$$

CREATE TRIGGER trg_payment_recovery_proposals_no_delete
BEFORE DELETE ON payment_recovery_proposals
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'payment recovery proposals are immutable';
END$$

CREATE TRIGGER trg_payment_recovery_approvals_no_update
BEFORE UPDATE ON payment_recovery_approvals
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'payment recovery approvals are immutable';
END$$

CREATE TRIGGER trg_payment_recovery_approvals_no_delete
BEFORE DELETE ON payment_recovery_approvals
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'payment recovery approvals are immutable';
END$$

DELIMITER ;
