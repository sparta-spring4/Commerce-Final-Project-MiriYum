CREATE TABLE menu_search_profiles (
    menu_search_profile_id BIGINT NOT NULL AUTO_INCREMENT,
    menu_version_id BIGINT NOT NULL,
    schema_version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (menu_search_profile_id),
    CONSTRAINT uk_menu_search_profiles_version UNIQUE (menu_version_id),
    CONSTRAINT fk_menu_search_profiles_version
        FOREIGN KEY (menu_version_id)
        REFERENCES menu_versions (menu_version_id) ON DELETE RESTRICT,
    CONSTRAINT ck_menu_search_profiles_schema_version
        CHECK (CHAR_LENGTH(TRIM(schema_version)) BETWEEN 1 AND 40)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE menu_search_profile_terms (
    menu_search_profile_term_id BIGINT NOT NULL AUTO_INCREMENT,
    menu_search_profile_id BIGINT NOT NULL,
    dimension VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalized_term VARCHAR(60) NOT NULL,
    confidence DECIMAL(5, 4) NOT NULL,
    source VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (menu_search_profile_term_id),
    CONSTRAINT uk_menu_search_profile_terms_value
        UNIQUE (menu_search_profile_id, dimension, normalized_term),
    CONSTRAINT fk_menu_search_profile_terms_profile
        FOREIGN KEY (menu_search_profile_id)
        REFERENCES menu_search_profiles (menu_search_profile_id) ON DELETE CASCADE,
    CONSTRAINT ck_menu_search_profile_terms_dimension CHECK (dimension IN (
        'MENU_FAMILY', 'ALIAS', 'INGREDIENT', 'TASTE', 'BROTH',
        'METHOD', 'AROMA', 'TEXTURE', 'FORM'
    )),
    CONSTRAINT ck_menu_search_profile_terms_value
        CHECK (CHAR_LENGTH(TRIM(normalized_term)) BETWEEN 1 AND 60),
    CONSTRAINT ck_menu_search_profile_terms_confidence
        CHECK (confidence BETWEEN 0.0000 AND 1.0000),
    CONSTRAINT ck_menu_search_profile_terms_source
        CHECK (source IN ('CURATED', 'RULE_DERIVED', 'LLM_DERIVED')),
    INDEX idx_menu_search_profile_terms_lookup (
        dimension, normalized_term, menu_search_profile_id
    ),
    INDEX idx_menu_search_profile_terms_profile (
        menu_search_profile_id, dimension
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
