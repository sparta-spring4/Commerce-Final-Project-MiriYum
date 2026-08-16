-- Issue #271: store-scoped Waiting runtime settings and immutable audit snapshots.
CREATE TABLE waiting_settings (
    waiting_setting_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    reception_mode VARCHAR(16) NOT NULL,
    advance_open_minutes INT NOT NULL,
    version BIGINT NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (waiting_setting_id),
    CONSTRAINT uk_waiting_settings_store UNIQUE (store_id),
    CONSTRAINT fk_waiting_settings_store FOREIGN KEY (store_id)
        REFERENCES stores (store_id) ON DELETE RESTRICT,
    CONSTRAINT ck_waiting_settings_mode
        CHECK (reception_mode IN ('AUTO', 'MANUAL', 'PAUSED')),
    CONSTRAINT ck_waiting_settings_minutes
        CHECK (advance_open_minutes BETWEEN 0 AND 180),
    CONSTRAINT ck_waiting_settings_disabled_paused
        CHECK (enabled OR reception_mode = 'PAUSED'),
    CONSTRAINT ck_waiting_settings_version CHECK (version >= 1),
    CONSTRAINT ck_waiting_settings_lock_version CHECK (lock_version >= 0)
);

CREATE TABLE waiting_setting_audits (
    waiting_setting_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    settings_version BIGINT NOT NULL,
    operator_account_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    reception_mode VARCHAR(16) NOT NULL,
    advance_open_minutes INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (waiting_setting_audit_id),
    CONSTRAINT uk_waiting_setting_audits_store_version
        UNIQUE (store_id, settings_version),
    CONSTRAINT fk_waiting_setting_audits_store FOREIGN KEY (store_id)
        REFERENCES stores (store_id) ON DELETE RESTRICT,
    CONSTRAINT ck_waiting_setting_audits_mode
        CHECK (reception_mode IN ('AUTO', 'MANUAL', 'PAUSED')),
    CONSTRAINT ck_waiting_setting_audits_minutes
        CHECK (advance_open_minutes BETWEEN 0 AND 180),
    CONSTRAINT ck_waiting_setting_audits_disabled_paused
        CHECK (enabled OR reception_mode = 'PAUSED'),
    CONSTRAINT ck_waiting_setting_audits_version CHECK (settings_version >= 1),
    INDEX idx_waiting_setting_audits_store_created (store_id, created_at)
);
