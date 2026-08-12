-- Refresh Token 재사용 위험 사건을 중앙에서 기록한다.
CREATE TABLE auth_risk_events (
    auth_risk_event_id BIGINT NOT NULL AUTO_INCREMENT,
    event_key VARCHAR(255) NOT NULL,
    namespace VARCHAR(32) NOT NULL,
    account_id BIGINT NOT NULL,
    family_id VARCHAR(255) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    source_event VARCHAR(64) NOT NULL,
    origin_event VARCHAR(64) NOT NULL,
    policy_version VARCHAR(32) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    occurrence_count BIGINT NOT NULL,
    last_occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (auth_risk_event_id),
    CONSTRAINT uk_auth_risk_events_event_key UNIQUE (event_key),
    CONSTRAINT ck_auth_risk_events_occurrence_count CHECK (occurrence_count >= 1)
);
