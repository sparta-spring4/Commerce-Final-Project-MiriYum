-- Distinguishes logical deletion from completed object cleanup for durable reconciliation.
ALTER TABLE file_metadata
    ADD COLUMN object_cleanup_completed_at DATETIME(6) NULL,
    ADD CONSTRAINT chk_file_metadata_object_cleanup_completed_at
        CHECK (object_cleanup_completed_at IS NULL OR storage_status = 'DELETED');

-- Bounded reconciliation reads only pending physical cleanup or stale PENDING rows.
CREATE INDEX idx_file_metadata_object_cleanup_pending
    ON file_metadata (storage_status, object_cleanup_completed_at, deleted_at);

CREATE INDEX idx_file_metadata_stale_pending_cleanup
    ON file_metadata (storage_status, created_at);
