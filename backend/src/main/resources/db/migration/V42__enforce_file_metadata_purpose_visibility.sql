-- Enforce the same purpose/visibility combinations at the MySQL boundary as the Java domain model.
ALTER TABLE file_metadata
    DROP CHECK ck_file_metadata_purpose_visibility,
    ADD CONSTRAINT ck_file_metadata_purpose_visibility
        CHECK (
            (purpose = 'BUSINESS_LICENSE' AND visibility = 'PRIVATE')
            OR (purpose IN ('STORE_IMAGE', 'MENU_IMAGE') AND visibility = 'PUBLIC')
        );
