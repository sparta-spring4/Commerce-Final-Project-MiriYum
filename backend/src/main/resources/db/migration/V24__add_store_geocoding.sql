ALTER TABLE stores
    ADD COLUMN address_version BIGINT NOT NULL DEFAULT 1 AFTER address,
    ADD COLUMN geocoding_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED'
        AFTER address_version,
    ADD COLUMN latitude DECIMAL(18, 15) NULL AFTER geocoding_status,
    ADD COLUMN longitude DECIMAL(18, 15) NULL AFTER latitude,
    ADD COLUMN verified_address VARCHAR(300) NULL AFTER longitude,
    ADD COLUMN geocoding_verified_at DATETIME(6) NULL AFTER verified_address,
    ADD COLUMN geocoding_address_version BIGINT NULL AFTER geocoding_verified_at,
    ADD CONSTRAINT ck_stores_address_version
        CHECK (address_version >= 1),
    ADD CONSTRAINT ck_stores_geocoding_status
        CHECK (geocoding_status IN ('UNVERIFIED', 'VERIFIED')),
    ADD CONSTRAINT ck_stores_geocoding_shape
        CHECK (
            (
                geocoding_status = 'UNVERIFIED'
                AND latitude IS NULL
                AND longitude IS NULL
                AND verified_address IS NULL
                AND geocoding_verified_at IS NULL
                AND geocoding_address_version IS NULL
            )
            OR
            (
                geocoding_status = 'VERIFIED'
                AND latitude BETWEEN -90 AND 90
                AND longitude BETWEEN -180 AND 180
                AND verified_address IS NOT NULL
                AND CHAR_LENGTH(TRIM(verified_address)) > 0
                AND geocoding_verified_at IS NOT NULL
                AND geocoding_address_version = address_version
            )
        );
