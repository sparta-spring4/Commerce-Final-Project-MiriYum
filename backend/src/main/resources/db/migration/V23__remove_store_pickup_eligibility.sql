ALTER TABLE stores
    DROP CHECK ck_stores_pickup_eligibility,
    DROP COLUMN pickup_eligibility;
