CREATE TABLE store_business_registration_evidences (
    evidence_id               CHAR(36)     COLLATE utf8mb4_0900_as_cs NOT NULL,
    onboarding_application_id BIGINT       NOT NULL,
    application_version       BIGINT       NOT NULL,
    store_operator_account_id BIGINT       NOT NULL,
    file_id                   CHAR(36)     COLLATE utf8mb4_0900_as_cs NOT NULL,
    evidence_status           VARCHAR(20)  NOT NULL,
    current_marker            INT          NULL,
    retention_due_at          DATETIME(6)  NULL,
    replaced_at               DATETIME(6)  NULL,
    created_at                DATETIME(6)  NOT NULL,
    version                   BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (evidence_id),
    CONSTRAINT uk_store_business_registration_evidence_file UNIQUE (file_id),
    CONSTRAINT uk_store_business_registration_current
        UNIQUE (onboarding_application_id, application_version, current_marker),
    CONSTRAINT fk_store_business_registration_evidence_file
        FOREIGN KEY (file_id) REFERENCES file_metadata (file_id),
    CONSTRAINT ck_store_business_registration_evidence_ids
        CHECK (onboarding_application_id > 0 AND application_version > 0 AND store_operator_account_id > 0),
    CONSTRAINT ck_store_business_registration_evidence_status
        CHECK (evidence_status IN ('CURRENT', 'REPLACED')),
    CONSTRAINT ck_store_business_registration_evidence_current
        CHECK (
            (evidence_status = 'CURRENT' AND current_marker = 1
                AND retention_due_at IS NULL AND replaced_at IS NULL)
            OR (evidence_status = 'REPLACED' AND current_marker IS NULL
                AND retention_due_at IS NOT NULL AND replaced_at IS NOT NULL)
        )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
