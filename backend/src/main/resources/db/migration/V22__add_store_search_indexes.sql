CREATE INDEX idx_stores_public_search
    ON stores (verification_status ASC, name ASC, store_id ASC);

CREATE INDEX idx_stores_public_search_name_desc
    ON stores (verification_status ASC, name DESC, store_id ASC);

CREATE INDEX idx_stores_public_search_created_at_asc
    ON stores (verification_status ASC, created_at ASC, store_id ASC);

CREATE INDEX idx_stores_public_search_created_at_desc
    ON stores (verification_status ASC, created_at DESC, store_id ASC);

CREATE INDEX idx_menus_public_search
    ON menus (store_id, retired, visibility, published_version_number);
