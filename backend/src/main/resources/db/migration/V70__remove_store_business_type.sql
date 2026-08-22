ALTER TABLE stores
    DROP CHECK ck_stores_business_type,
    DROP COLUMN business_type;

ALTER TABLE store_onboarding_application_versions
    DROP COLUMN business_type;
