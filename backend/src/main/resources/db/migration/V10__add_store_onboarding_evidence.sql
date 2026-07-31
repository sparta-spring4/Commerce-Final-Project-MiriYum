ALTER TABLE stores
    ADD COLUMN applicant_self_attested_at DATETIME(6) NULL,
    ADD COLUMN required_terms_agreed_at DATETIME(6) NULL,
    ADD COLUMN required_terms_version VARCHAR(50) NULL;

UPDATE stores
SET applicant_self_attested_at = created_at,
    required_terms_agreed_at = created_at,
    required_terms_version = 'STORE_ONBOARDING_REQUIRED_TERMS_V1'
WHERE applicant_self_attested_at IS NULL
   OR required_terms_agreed_at IS NULL
   OR required_terms_version IS NULL;

ALTER TABLE stores
    MODIFY applicant_self_attested_at DATETIME(6) NOT NULL,
    MODIFY required_terms_agreed_at DATETIME(6) NOT NULL,
    MODIFY required_terms_version VARCHAR(50) NOT NULL,
    ADD CONSTRAINT ck_stores_required_terms_version
        CHECK (CHAR_LENGTH(TRIM(required_terms_version)) > 0);
