CREATE INDEX idx_stores_public_search
    ON stores (verification_status, name, store_id);

CREATE INDEX idx_menus_public_search
    ON menus (store_id, retired, visibility, published_version_number);
