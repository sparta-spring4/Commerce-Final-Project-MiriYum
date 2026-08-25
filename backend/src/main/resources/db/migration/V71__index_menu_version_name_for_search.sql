ALTER TABLE menu_versions
    ADD INDEX idx_menu_versions_search_name (
        name,
        status,
        menu_id,
        version_number
    );
