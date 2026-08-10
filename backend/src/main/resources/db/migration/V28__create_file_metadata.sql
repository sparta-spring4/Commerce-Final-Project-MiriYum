-- Stores file lifecycle metadata only. File bytes belong to the storage provider.
CREATE TABLE file_metadata (
    file_id          CHAR(36)     COLLATE utf8mb4_0900_as_cs NOT NULL,
    owner_type       VARCHAR(32)  COLLATE utf8mb4_0900_as_cs NOT NULL,
    owner_id         BIGINT       NOT NULL,
    purpose          VARCHAR(32)  NOT NULL,
    object_key       VARCHAR(512) COLLATE utf8mb4_0900_as_cs NOT NULL,
    content_type     VARCHAR(128) NOT NULL,
    size_bytes       BIGINT       NOT NULL,
    checksum         CHAR(64)     COLLATE utf8mb4_0900_as_cs NOT NULL,
    visibility       VARCHAR(20)  NOT NULL,
    storage_status   VARCHAR(20)  NOT NULL,
    retention_policy VARCHAR(64)  COLLATE utf8mb4_0900_as_cs NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    deleted_at       DATETIME(6)  NULL,
    PRIMARY KEY (file_id),
    CONSTRAINT uk_file_metadata_object_key UNIQUE (object_key),
    CONSTRAINT ck_file_metadata_owner_id CHECK (owner_id > 0),
    CONSTRAINT ck_file_metadata_size_bytes CHECK (size_bytes >= 0),
    CONSTRAINT ck_file_metadata_purpose
        CHECK (purpose IN ('BUSINESS_LICENSE', 'STORE_IMAGE', 'MENU_IMAGE')),
    CONSTRAINT ck_file_metadata_visibility
        CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
    CONSTRAINT ck_file_metadata_status
        CHECK (storage_status IN ('PENDING', 'CONFIRMED', 'FAILED', 'DELETED')),
    CONSTRAINT ck_file_metadata_deleted_at
        CHECK (
            (storage_status = 'DELETED' AND deleted_at IS NOT NULL)
            OR (storage_status <> 'DELETED' AND deleted_at IS NULL)
        )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
