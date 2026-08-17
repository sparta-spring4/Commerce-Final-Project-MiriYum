-- Logical deletion must survive an object-store outage. These fields make the
-- cleanup retry boundary durable without exposing object keys in application logs.
ALTER TABLE file_metadata
    ADD COLUMN cleanup_requested_at DATETIME(6) NULL AFTER deleted_at,
    ADD COLUMN cleanup_last_attempt_at DATETIME(6) NULL AFTER cleanup_requested_at,
    ADD COLUMN cleanup_completed_at DATETIME(6) NULL AFTER cleanup_last_attempt_at,
    ADD COLUMN cleanup_attempts BIGINT NOT NULL DEFAULT 0 AFTER cleanup_completed_at,
    ADD INDEX ix_file_metadata_cleanup_pending (storage_status, cleanup_completed_at, cleanup_requested_at);

UPDATE file_metadata
SET cleanup_requested_at = deleted_at
WHERE storage_status = 'DELETED'
  AND cleanup_requested_at IS NULL;
