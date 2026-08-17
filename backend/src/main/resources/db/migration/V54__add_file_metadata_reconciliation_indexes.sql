-- Keeps failed object cleanup retryable without allowing a stale worker to delete a newer generation.
ALTER TABLE file_metadata
    ADD COLUMN object_cleanup_completed_at DATETIME(6) NULL,
    ADD COLUMN object_cleanup_next_attempt_at DATETIME(6) NULL,
    ADD COLUMN object_cleanup_claimed_until DATETIME(6) NULL,
    ADD COLUMN object_cleanup_claim_token VARCHAR(36) NULL,
    ADD COLUMN object_cleanup_failure_count INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_file_metadata_object_cleanup_completed_at
        CHECK (object_cleanup_completed_at IS NULL OR storage_status = 'DELETED');

UPDATE file_metadata
SET object_cleanup_next_attempt_at = deleted_at
WHERE storage_status = 'DELETED'
  AND object_cleanup_completed_at IS NULL;

-- Bounded reconciliation reads due cleanup or stale PENDING rows without scanning the table.
CREATE INDEX idx_file_metadata_object_cleanup_pending
    ON file_metadata (storage_status, object_cleanup_completed_at, object_cleanup_next_attempt_at, deleted_at);

CREATE INDEX idx_file_metadata_stale_pending_cleanup
    ON file_metadata (storage_status, created_at);
