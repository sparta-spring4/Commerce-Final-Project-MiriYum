ALTER TABLE payments
    ADD store_id BIGINT NULL AFTER source_reference_id;

UPDATE payments p
LEFT JOIN reservation_holds rh
    ON p.source_type = 'RESERVATION_DEPOSIT'
   AND rh.reservation_hold_id = CAST(p.source_reference_id AS UNSIGNED)
LEFT JOIN waiting_teams wt
    ON p.source_type = 'WAITING_RESERVATION_DEPOSIT'
   AND wt.waiting_team_id = CAST(p.source_reference_id AS UNSIGNED)
SET p.store_id = CASE
    WHEN p.source_type = 'RESERVATION_DEPOSIT' THEN rh.store_id
    WHEN p.source_type = 'WAITING_RESERVATION_DEPOSIT' THEN wt.store_id
    ELSE NULL
END
WHERE p.store_id IS NULL;

ALTER TABLE payments
    MODIFY store_id BIGINT NOT NULL,
    ADD CONSTRAINT chk_payments_store_id CHECK (store_id > 0),
    ADD INDEX idx_payments_store_source (store_id, source_type, source_reference_id);

CREATE TABLE payment_monitoring_snapshots (
    payment_monitoring_snapshot_id BIGINT NOT NULL AUTO_INCREMENT,
    payment_pk BIGINT NOT NULL,
    payment_id VARCHAR(19) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_reference_id VARCHAR(19) NOT NULL,
    store_id BIGINT NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    source_status VARCHAR(32) NOT NULL,
    status_version BIGINT NOT NULL,
    status_changed_at DATETIME(6) NOT NULL,
    amount_minor BIGINT NOT NULL,
    refunded_amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    captured_at DATETIME(6) NOT NULL,
    PRIMARY KEY (payment_monitoring_snapshot_id),
    CONSTRAINT uk_payment_monitoring_version UNIQUE (payment_pk, status_version),
    CONSTRAINT fk_payment_monitoring_payment
        FOREIGN KEY (payment_pk) REFERENCES payments (payment_pk) ON DELETE RESTRICT,
    CONSTRAINT chk_payment_monitoring_event_type
        CHECK (event_type IN ('BASELINE', 'CREATED', 'TRANSITION')),
    CONSTRAINT chk_payment_monitoring_status_version CHECK (status_version >= 0),
    CONSTRAINT chk_payment_monitoring_store_id CHECK (store_id > 0),
    CONSTRAINT chk_payment_monitoring_amounts CHECK (
        amount_minor > 0
        AND refunded_amount_minor >= 0
        AND refunded_amount_minor <= amount_minor
    ),
    INDEX idx_payment_monitoring_changes (
        store_id, status_changed_at DESC, payment_monitoring_snapshot_id DESC
    ),
    INDEX idx_payment_monitoring_case (
        source_type, source_reference_id, status_changed_at DESC
    )
) ENGINE = InnoDB;

CREATE TABLE payment_refund_monitoring_snapshots (
    payment_refund_monitoring_snapshot_id BIGINT NOT NULL AUTO_INCREMENT,
    payment_refund_pk BIGINT NOT NULL,
    payment_pk BIGINT NOT NULL,
    refund_id VARCHAR(19) NOT NULL,
    store_id BIGINT NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    source_status VARCHAR(32) NOT NULL,
    status_version BIGINT NOT NULL,
    status_changed_at DATETIME(6) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    captured_at DATETIME(6) NOT NULL,
    PRIMARY KEY (payment_refund_monitoring_snapshot_id),
    CONSTRAINT uk_payment_refund_monitoring_version
        UNIQUE (payment_refund_pk, status_version),
    CONSTRAINT fk_payment_refund_monitoring_refund
        FOREIGN KEY (payment_refund_pk)
        REFERENCES payment_refunds (payment_refund_pk) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_refund_monitoring_payment
        FOREIGN KEY (payment_pk) REFERENCES payments (payment_pk) ON DELETE RESTRICT,
    CONSTRAINT chk_payment_refund_monitoring_event_type
        CHECK (event_type IN ('BASELINE', 'CREATED', 'TRANSITION')),
    CONSTRAINT chk_payment_refund_monitoring_status_version CHECK (status_version >= 0),
    CONSTRAINT chk_payment_refund_monitoring_store_id CHECK (store_id > 0),
    CONSTRAINT chk_payment_refund_monitoring_amount CHECK (amount_minor > 0),
    INDEX idx_payment_refund_monitoring_payment (
        payment_pk, status_changed_at ASC, payment_refund_monitoring_snapshot_id ASC
    )
) ENGINE = InnoDB;

SET @payment_monitoring_baseline_at = UTC_TIMESTAMP(6);

INSERT INTO payment_monitoring_snapshots (
    payment_pk, payment_id, source_type, source_reference_id, store_id,
    event_type, source_status, status_version, status_changed_at,
    amount_minor, refunded_amount_minor, currency, captured_at
)
SELECT payment_pk, payment_id, source_type, source_reference_id, store_id,
       'BASELINE', status, version, @payment_monitoring_baseline_at,
       amount_minor, refunded_amount_minor, currency, @payment_monitoring_baseline_at
FROM payments;

INSERT INTO payment_refund_monitoring_snapshots (
    payment_refund_pk, payment_pk, refund_id, store_id,
    event_type, source_status, status_version, status_changed_at,
    amount_minor, currency, requested_at, completed_at, captured_at
)
SELECT r.payment_refund_pk, r.payment_pk, r.refund_id, p.store_id,
       'BASELINE', r.status, r.version, @payment_monitoring_baseline_at,
       r.amount_minor, r.currency, r.requested_at, r.completed_at,
       @payment_monitoring_baseline_at
FROM payment_refunds r
JOIN payments p ON p.payment_pk = r.payment_pk;

DELIMITER $$

CREATE TRIGGER trg_payments_monitoring_after_insert
AFTER INSERT ON payments
FOR EACH ROW
BEGIN
    INSERT INTO payment_monitoring_snapshots (
        payment_pk, payment_id, source_type, source_reference_id, store_id,
        event_type, source_status, status_version, status_changed_at,
        amount_minor, refunded_amount_minor, currency, captured_at
    ) VALUES (
        NEW.payment_pk, NEW.payment_id, NEW.source_type, NEW.source_reference_id, NEW.store_id,
        'CREATED', NEW.status, NEW.version, NEW.created_at,
        NEW.amount_minor, NEW.refunded_amount_minor, NEW.currency, UTC_TIMESTAMP(6)
    );
END$$

CREATE TRIGGER trg_payments_monitoring_after_update
AFTER UPDATE ON payments
FOR EACH ROW
BEGIN
    IF NOT (NEW.status <=> OLD.status)
            OR NOT (NEW.refunded_amount_minor <=> OLD.refunded_amount_minor) THEN
        INSERT INTO payment_monitoring_snapshots (
            payment_pk, payment_id, source_type, source_reference_id, store_id,
            event_type, source_status, status_version, status_changed_at,
            amount_minor, refunded_amount_minor, currency, captured_at
        ) VALUES (
            NEW.payment_pk, NEW.payment_id, NEW.source_type, NEW.source_reference_id, NEW.store_id,
            'TRANSITION', NEW.status, NEW.version, NEW.updated_at,
            NEW.amount_minor, NEW.refunded_amount_minor, NEW.currency, UTC_TIMESTAMP(6)
        );
    END IF;
END$$

CREATE TRIGGER trg_payment_refunds_monitoring_after_insert
AFTER INSERT ON payment_refunds
FOR EACH ROW
BEGIN
    INSERT INTO payment_refund_monitoring_snapshots (
        payment_refund_pk, payment_pk, refund_id, store_id,
        event_type, source_status, status_version, status_changed_at,
        amount_minor, currency, requested_at, completed_at, captured_at
    ) SELECT NEW.payment_refund_pk, NEW.payment_pk, NEW.refund_id, p.store_id,
             'CREATED', NEW.status, NEW.version, NEW.requested_at,
             NEW.amount_minor, NEW.currency, NEW.requested_at, NEW.completed_at,
             UTC_TIMESTAMP(6)
        FROM payments p WHERE p.payment_pk = NEW.payment_pk;
END$$

CREATE TRIGGER trg_payment_refunds_monitoring_after_update
AFTER UPDATE ON payment_refunds
FOR EACH ROW
BEGIN
    IF NOT (NEW.status <=> OLD.status)
            OR NOT (NEW.completed_at <=> OLD.completed_at) THEN
        INSERT INTO payment_refund_monitoring_snapshots (
            payment_refund_pk, payment_pk, refund_id, store_id,
            event_type, source_status, status_version, status_changed_at,
            amount_minor, currency, requested_at, completed_at, captured_at
        ) SELECT NEW.payment_refund_pk, NEW.payment_pk, NEW.refund_id, p.store_id,
                 'TRANSITION', NEW.status, NEW.version, NEW.updated_at,
                 NEW.amount_minor, NEW.currency, NEW.requested_at, NEW.completed_at,
                 UTC_TIMESTAMP(6)
            FROM payments p WHERE p.payment_pk = NEW.payment_pk;
    END IF;
END$$

CREATE TRIGGER trg_payment_monitoring_snapshots_no_update
BEFORE UPDATE ON payment_monitoring_snapshots
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'payment monitoring snapshots are immutable';
END$$

CREATE TRIGGER trg_payment_monitoring_snapshots_no_delete
BEFORE DELETE ON payment_monitoring_snapshots
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'payment monitoring snapshots are immutable';
END$$

CREATE TRIGGER trg_payment_refund_monitoring_snapshots_no_update
BEFORE UPDATE ON payment_refund_monitoring_snapshots
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'payment refund monitoring snapshots are immutable';
END$$

CREATE TRIGGER trg_payment_refund_monitoring_snapshots_no_delete
BEFORE DELETE ON payment_refund_monitoring_snapshots
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'payment refund monitoring snapshots are immutable';
END$$

DELIMITER ;
