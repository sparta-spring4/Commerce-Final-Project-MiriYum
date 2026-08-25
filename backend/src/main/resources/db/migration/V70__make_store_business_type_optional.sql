ALTER TABLE stores
    MODIFY COLUMN business_type VARCHAR(20) NULL;

ALTER TABLE store_onboarding_application_versions
    MODIFY COLUMN business_type VARCHAR(20) NULL;
