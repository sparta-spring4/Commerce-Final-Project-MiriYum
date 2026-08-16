-- Supports batched public menu image lookup without scanning unrelated file history.
CREATE INDEX idx_file_metadata_public_menu_lookup
    ON file_metadata (
        owner_type,
        purpose,
        visibility,
        storage_status,
        owner_id,
        created_at
    );
