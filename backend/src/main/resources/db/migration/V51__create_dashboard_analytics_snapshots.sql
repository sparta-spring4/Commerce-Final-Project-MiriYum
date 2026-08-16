ALTER TABLE stores
    ADD COLUMN dashboard_authority_version BIGINT NOT NULL DEFAULT 1
        AFTER time_zone_id,
    ADD CONSTRAINT ck_stores_dashboard_authority_version
        CHECK (dashboard_authority_version >= 1);

ALTER TABLE reservation_capacity_buckets
    ADD COLUMN policy_published_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6) AFTER policy_version,
    ADD INDEX ix_reservation_capacity_policy_effectivity (
        store_id,
        service_date,
        policy_published_at,
        policy_version
    );

CREATE TABLE dashboard_analytics_snapshots (
    dashboard_snapshot_id BIGINT NOT NULL AUTO_INCREMENT,
    public_snapshot_id CHAR(36) NOT NULL,
    store_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    time_zone_id VARCHAR(64) NOT NULL,
    as_of DATETIME(6) NOT NULL,
    generated_at DATETIME(6) NOT NULL,
    store_authority_version BIGINT NOT NULL,
    aggregation_version BIGINT NOT NULL,
    latest_marker BOOLEAN NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (dashboard_snapshot_id),
    CONSTRAINT uk_dashboard_analytics_snapshot_public_id
        UNIQUE (public_snapshot_id),
    CONSTRAINT uk_dashboard_analytics_snapshot_identity
        UNIQUE (store_id, business_date, as_of, store_authority_version),
    CONSTRAINT uk_dashboard_analytics_snapshot_latest
        UNIQUE (store_id, business_date, latest_marker),
    CONSTRAINT fk_dashboard_analytics_snapshot_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_dashboard_analytics_snapshot_time_zone
        CHECK (time_zone_id = 'Asia/Seoul'),
    CONSTRAINT ck_dashboard_analytics_snapshot_authority_version
        CHECK (store_authority_version >= 1),
    CONSTRAINT ck_dashboard_analytics_snapshot_aggregation_version
        CHECK (aggregation_version >= 1),
    CONSTRAINT ck_dashboard_analytics_snapshot_latest_marker
        CHECK (latest_marker IS NULL OR latest_marker = TRUE),
    CONSTRAINT ck_dashboard_analytics_snapshot_minute_boundary
        CHECK (SECOND(as_of) = 0 AND MICROSECOND(as_of) = 0),
    INDEX ix_dashboard_analytics_snapshot_store_date_generated
        (store_id, business_date, generated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE dashboard_analytics_metric_snapshots (
    dashboard_metric_snapshot_id BIGINT NOT NULL AUTO_INCREMENT,
    dashboard_snapshot_id BIGINT NOT NULL,
    metric_key VARCHAR(50) COLLATE utf8mb4_0900_as_cs NOT NULL,
    definition_version VARCHAR(100) COLLATE utf8mb4_0900_as_cs NOT NULL,
    aggregation_version BIGINT NOT NULL,
    as_of DATETIME(6) NOT NULL,
    data_through DATETIME(6) NULL,
    input_checkpoint VARCHAR(255) NULL,
    completeness VARCHAR(20) COLLATE utf8mb4_0900_as_cs NOT NULL,
    corrected BOOLEAN NOT NULL,
    reason_code VARCHAR(60) COLLATE utf8mb4_0900_as_cs NULL,
    value_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (dashboard_metric_snapshot_id),
    CONSTRAINT uk_dashboard_analytics_metric_key
        UNIQUE (dashboard_snapshot_id, metric_key),
    CONSTRAINT fk_dashboard_analytics_metric_snapshot
        FOREIGN KEY (dashboard_snapshot_id)
        REFERENCES dashboard_analytics_snapshots (dashboard_snapshot_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_dashboard_analytics_metric_key
        CHECK (metric_key IN (
            'TODAY_RESERVATION_TEAMS',
            'RESERVATION_RATE',
            'TEAM_CAPACITY_UTILIZATION',
            'CANCELLATION_RATE',
            'WAITING_STATUS',
            'NO_SHOW_STATUS'
        )),
    CONSTRAINT ck_dashboard_analytics_metric_definition_version
        CHECK (CHAR_LENGTH(TRIM(definition_version)) > 0),
    CONSTRAINT ck_dashboard_analytics_metric_aggregation_version
        CHECK (aggregation_version >= 1),
    CONSTRAINT ck_dashboard_analytics_metric_completeness
        CHECK (completeness IN ('COMPLETE', 'PARTIAL', 'DELAYED', 'UNAVAILABLE')),
    INDEX ix_dashboard_analytics_metric_snapshot
        (dashboard_snapshot_id, metric_key, aggregation_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
