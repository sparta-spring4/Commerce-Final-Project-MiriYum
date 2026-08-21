CREATE TABLE store_onboarding_applications (
    store_onboarding_application_id BIGINT       NOT NULL AUTO_INCREMENT,
    store_operator_account_id        BIGINT       NOT NULL,
    submission_idempotency_key       VARCHAR(100) COLLATE utf8mb4_0900_as_cs NOT NULL,
    submission_fingerprint           CHAR(64)     COLLATE utf8mb4_0900_as_cs NOT NULL,
    current_version                  BIGINT       NOT NULL,
    status                           VARCHAR(30)  NOT NULL,
    review_required                  BOOLEAN      NOT NULL,
    resulting_store_id               BIGINT       NULL,
    created_at                       DATETIME(6)  NOT NULL,
    updated_at                       DATETIME(6)  NOT NULL,
    row_version                      BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (store_onboarding_application_id),
    CONSTRAINT uk_store_onboarding_submission
        UNIQUE (store_operator_account_id, submission_idempotency_key),
    CONSTRAINT uk_store_onboarding_result_store UNIQUE (resulting_store_id),
    CONSTRAINT fk_store_onboarding_application_operator
        FOREIGN KEY (store_operator_account_id)
        REFERENCES store_operator_accounts (store_operator_account_id),
    CONSTRAINT fk_store_onboarding_application_result_store
        FOREIGN KEY (resulting_store_id) REFERENCES stores (store_id),
    CONSTRAINT ck_store_onboarding_application_version CHECK (current_version > 0),
    CONSTRAINT ck_store_onboarding_application_status CHECK (status IN (
        'RECEIVED', 'EVIDENCE_PENDING', 'AUTO_CHECKING', 'REVIEW_READY', 'UNDER_REVIEW',
        'CHANGES_REQUESTED', 'AUTO_APPROVED', 'APPROVED', 'REJECTED'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE store_onboarding_application_versions (
    store_onboarding_application_version_id BIGINT       NOT NULL AUTO_INCREMENT,
    store_onboarding_application_id         BIGINT       NOT NULL,
    application_version                     BIGINT       NOT NULL,
    supplement_idempotency_key              VARCHAR(100) COLLATE utf8mb4_0900_as_cs NULL,
    request_fingerprint                     CHAR(64)     COLLATE utf8mb4_0900_as_cs NOT NULL,
    business_registration_evidence_id       CHAR(36)     COLLATE utf8mb4_0900_as_cs NOT NULL,
    business_registration_number            VARCHAR(10)  NOT NULL,
    business_type                           VARCHAR(20)  NOT NULL,
    name                                    VARCHAR(100) NOT NULL,
    description                             VARCHAR(1000) NOT NULL,
    region                                  VARCHAR(20)  NOT NULL,
    address                                 VARCHAR(300) NOT NULL,
    time_zone_id                            VARCHAR(64)  NOT NULL,
    store_category_code                     VARCHAR(50)  COLLATE utf8mb4_0900_as_cs NOT NULL,
    tag_codes_json                          JSON         NOT NULL,
    reservation_enabled                     BOOLEAN      NOT NULL,
    menu_hold_enabled                       BOOLEAN      NOT NULL,
    pickup_enabled                          BOOLEAN      NOT NULL,
    applicant_self_attested_at              DATETIME(6)  NOT NULL,
    required_terms_agreed_at                DATETIME(6)  NOT NULL,
    required_terms_version                  VARCHAR(50)  NOT NULL,
    review_required                         BOOLEAN      NOT NULL,
    automatic_check_policy_version          VARCHAR(50)  NOT NULL,
    manual_review_policy_version             VARCHAR(50)  NOT NULL,
    created_at                              DATETIME(6)  NOT NULL,
    PRIMARY KEY (store_onboarding_application_version_id),
    CONSTRAINT uk_store_onboarding_version
        UNIQUE (store_onboarding_application_id, application_version),
    CONSTRAINT uk_store_onboarding_supplement
        UNIQUE (store_onboarding_application_id, supplement_idempotency_key),
    CONSTRAINT uk_store_onboarding_version_evidence
        UNIQUE (business_registration_evidence_id),
    CONSTRAINT fk_store_onboarding_version_application
        FOREIGN KEY (store_onboarding_application_id)
        REFERENCES store_onboarding_applications (store_onboarding_application_id),
    CONSTRAINT fk_store_onboarding_version_evidence
        FOREIGN KEY (business_registration_evidence_id)
        REFERENCES store_business_registration_evidences (evidence_id),
    CONSTRAINT ck_store_onboarding_snapshot_version CHECK (application_version > 0),
    CONSTRAINT ck_store_onboarding_business_number
        CHECK (business_registration_number REGEXP '^[0-9]{10}$'),
    CONSTRAINT ck_store_onboarding_tag_codes_json CHECK (JSON_TYPE(tag_codes_json) = 'ARRAY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE store_onboarding_automatic_check_jobs (
    store_onboarding_automatic_check_job_id BIGINT       NOT NULL AUTO_INCREMENT,
    store_onboarding_application_id         BIGINT       NOT NULL,
    application_version                     BIGINT       NOT NULL,
    status                                  VARCHAR(20)  NOT NULL,
    lease_owner                             VARCHAR(100) NULL,
    lease_token                             BIGINT       NOT NULL DEFAULT 0,
    lease_expires_at                        DATETIME(6)  NULL,
    attempt_count                           INT          NOT NULL DEFAULT 0,
    next_attempt_at                         DATETIME(6)  NOT NULL,
    result_code                             VARCHAR(50)  NULL,
    created_at                              DATETIME(6)  NOT NULL,
    updated_at                              DATETIME(6)  NOT NULL,
    row_version                             BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (store_onboarding_automatic_check_job_id),
    CONSTRAINT uk_store_onboarding_auto_check_job
        UNIQUE (store_onboarding_application_id, application_version),
    CONSTRAINT fk_store_onboarding_auto_check_version
        FOREIGN KEY (store_onboarding_application_id, application_version)
        REFERENCES store_onboarding_application_versions
            (store_onboarding_application_id, application_version),
    CONSTRAINT ck_store_onboarding_auto_check_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'PASSED', 'REJECTED', 'EXHAUSTED')),
    CONSTRAINT ck_store_onboarding_auto_check_counts
        CHECK (lease_token >= 0 AND attempt_count >= 0),
    INDEX ix_store_onboarding_auto_check_due (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE store_onboarding_review_cases (
    store_onboarding_review_case_id BIGINT       NOT NULL AUTO_INCREMENT,
    case_public_id                  CHAR(36)     COLLATE utf8mb4_0900_as_cs NOT NULL,
    store_onboarding_application_id BIGINT       NOT NULL,
    application_version             BIGINT       NOT NULL,
    case_type                       VARCHAR(30)  NOT NULL,
    status                          VARCHAR(30)  NOT NULL,
    case_version                    BIGINT       NOT NULL,
    active_marker                   INT          NULL,
    assigned_platform_operator_id   BIGINT       NULL,
    created_at                      DATETIME(6)  NOT NULL,
    updated_at                      DATETIME(6)  NOT NULL,
    row_version                     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (store_onboarding_review_case_id),
    CONSTRAINT uk_store_onboarding_case_public_id UNIQUE (case_public_id),
    CONSTRAINT uk_store_onboarding_active_case
        UNIQUE (store_onboarding_application_id, application_version, active_marker),
    CONSTRAINT fk_store_onboarding_review_case_version
        FOREIGN KEY (store_onboarding_application_id, application_version)
        REFERENCES store_onboarding_application_versions
            (store_onboarding_application_id, application_version),
    CONSTRAINT fk_store_onboarding_review_case_assignee
        FOREIGN KEY (assigned_platform_operator_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id),
    CONSTRAINT ck_store_onboarding_review_case_type
        CHECK (case_type IN ('ONBOARDING', 'OWNERSHIP_CONFLICT')),
    CONSTRAINT ck_store_onboarding_review_case_status CHECK (status IN (
        'REVIEW_READY', 'UNDER_REVIEW', 'CHANGES_REQUESTED', 'APPROVED', 'REJECTED', 'CLOSED'
    )),
    CONSTRAINT ck_store_onboarding_review_case_version CHECK (case_version > 0),
    CONSTRAINT ck_store_onboarding_review_case_active CHECK (
        (status IN ('REVIEW_READY', 'UNDER_REVIEW') AND active_marker = 1)
        OR (status IN ('CHANGES_REQUESTED', 'APPROVED', 'REJECTED', 'CLOSED') AND active_marker IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE store_onboarding_decisions (
    store_onboarding_decision_id    BIGINT       NOT NULL AUTO_INCREMENT,
    decision_public_id              CHAR(36)     COLLATE utf8mb4_0900_as_cs NOT NULL,
    case_public_id                  CHAR(36)     COLLATE utf8mb4_0900_as_cs NOT NULL,
    case_version                    BIGINT       NOT NULL,
    decided_by_platform_operator_id BIGINT       NOT NULL,
    decision_type                   VARCHAR(30)  NOT NULL,
    reason_code                     VARCHAR(50)  NOT NULL,
    reason_detail                   VARCHAR(1000) NULL,
    created_at                      DATETIME(6)  NOT NULL,
    PRIMARY KEY (store_onboarding_decision_id),
    CONSTRAINT uk_store_onboarding_decision_public_id UNIQUE (decision_public_id),
    CONSTRAINT uk_store_onboarding_terminal_decision UNIQUE (case_public_id, case_version),
    CONSTRAINT fk_store_onboarding_decision_case
        FOREIGN KEY (case_public_id)
        REFERENCES store_onboarding_review_cases (case_public_id),
    CONSTRAINT fk_store_onboarding_decision_operator
        FOREIGN KEY (decided_by_platform_operator_id)
        REFERENCES platform_operator_accounts (platform_operator_account_id),
    CONSTRAINT ck_store_onboarding_decision_type
        CHECK (decision_type IN ('APPROVE', 'REJECT', 'REQUEST_CHANGES')),
    CONSTRAINT ck_store_onboarding_decision_version CHECK (case_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$

CREATE TRIGGER trg_store_onboarding_versions_no_update
BEFORE UPDATE ON store_onboarding_application_versions
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'store onboarding versions are immutable';
END$$

CREATE TRIGGER trg_store_onboarding_versions_no_delete
BEFORE DELETE ON store_onboarding_application_versions
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'store onboarding versions are immutable';
END$$

CREATE TRIGGER trg_store_onboarding_decisions_no_update
BEFORE UPDATE ON store_onboarding_decisions
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'store onboarding decisions are immutable';
END$$

CREATE TRIGGER trg_store_onboarding_decisions_no_delete
BEFORE DELETE ON store_onboarding_decisions
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'store onboarding decisions are immutable';
END$$

DELIMITER ;
