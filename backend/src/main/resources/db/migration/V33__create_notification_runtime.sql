CREATE TABLE notification_tasks (
    notification_id BIGINT NOT NULL AUTO_INCREMENT,
    source_domain VARCHAR(32) NOT NULL,
    source_event_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    purpose VARCHAR(64) NOT NULL,
    recipient_account_id BIGINT NOT NULL,
    recipient_relation_version BIGINT NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id BIGINT NOT NULL,
    resource_version BIGINT NOT NULL,
    source_state VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    occurred_at DATETIME(0) NOT NULL,
    scheduled_at DATETIME(0) NOT NULL,
    expires_at DATETIME(0) NULL,
    timing_policy_version BIGINT NULL,
    correlation_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    contract_version VARCHAR(64) NOT NULL,
    payload_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    lease_owner VARCHAR(100) NULL,
    lease_token VARCHAR(100) NULL,
    lease_until DATETIME(6) NULL,
    last_error_code VARCHAR(100) NULL,
    title VARCHAR(100) NULL,
    delivered_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (notification_id),
    CONSTRAINT fk_notification_recipient
        FOREIGN KEY (recipient_account_id) REFERENCES consumer_accounts (consumer_account_id),
    CONSTRAINT chk_notification_recipient_relation_version CHECK (recipient_relation_version > 0),
    CONSTRAINT chk_notification_resource_version CHECK (resource_version > 0),
    CONSTRAINT chk_notification_timing_policy_version
        CHECK (timing_policy_version IS NULL OR timing_policy_version > 0),
    CONSTRAINT chk_notification_schedule CHECK (scheduled_at >= occurred_at),
    CONSTRAINT chk_notification_expiry CHECK (expires_at IS NULL OR expires_at >= occurred_at),
    CONSTRAINT chk_notification_status
        CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED', 'CANCELLED')),
    CONSTRAINT uk_notification_logical_event UNIQUE (
        source_domain,
        source_event_id,
        recipient_account_id,
        purpose,
        resource_type,
        resource_id,
        resource_version
    ),
    INDEX idx_notification_due (status, scheduled_at, next_attempt_at),
    INDEX idx_notification_history (recipient_account_id, occurred_at DESC, notification_id DESC)
) ENGINE = InnoDB;

CREATE TABLE notification_channel_attempts (
    notification_channel_attempt_id BIGINT NOT NULL AUTO_INCREMENT,
    notification_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    last_attempted_at DATETIME(6) NULL,
    failure_code VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (notification_channel_attempt_id),
    CONSTRAINT fk_notification_channel_task
        FOREIGN KEY (notification_id) REFERENCES notification_tasks (notification_id),
    CONSTRAINT chk_notification_channel CHECK (channel IN ('IN_APP')),
    CONSTRAINT chk_notification_attempt_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED', 'CANCELLED')),
    CONSTRAINT uk_notification_channel UNIQUE (notification_id, channel)
) ENGINE = InnoDB;

CREATE TABLE notification_task_transition_audits (
    notification_task_transition_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    notification_id BIGINT NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    reason VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (notification_task_transition_audit_id),
    CONSTRAINT fk_notification_transition_task
        FOREIGN KEY (notification_id) REFERENCES notification_tasks (notification_id),
    INDEX idx_notification_transition (notification_id, occurred_at)
) ENGINE = InnoDB;
