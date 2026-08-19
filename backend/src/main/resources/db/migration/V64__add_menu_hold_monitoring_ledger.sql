ALTER TABLE menu_holds
    ADD status_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_menu_holds_status_version CHECK (status_version >= 0);

CREATE TABLE menu_hold_transition_audits (
    menu_hold_transition_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    menu_hold_id BIGINT NOT NULL,
    reservation_id BIGINT NULL,
    reservation_hold_id BIGINT NULL,
    event_type VARCHAR(16) NOT NULL,
    before_status VARCHAR(32) NULL,
    after_status VARCHAR(32) NOT NULL,
    result_version BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (menu_hold_transition_audit_id),
    CONSTRAINT uk_menu_hold_transition_version UNIQUE (menu_hold_id, result_version),
    CONSTRAINT ck_menu_hold_transition_event_type
        CHECK (event_type IN ('BASELINE', 'CREATED', 'TRANSITION')),
    CONSTRAINT ck_menu_hold_transition_result_version CHECK (result_version >= 0),
    CONSTRAINT ck_menu_hold_transition_shape CHECK (
        (event_type IN ('BASELINE', 'CREATED') AND before_status IS NULL AND result_version = 0)
        OR (event_type = 'TRANSITION' AND before_status IS NOT NULL AND result_version > 0)
    ),
    CONSTRAINT fk_menu_hold_transition_hold
        FOREIGN KEY (menu_hold_id) REFERENCES menu_holds (menu_hold_id) ON DELETE RESTRICT
);

CREATE INDEX idx_menu_hold_transition_occurred
    ON menu_hold_transition_audits (occurred_at, menu_hold_id);

CREATE INDEX idx_menu_hold_transition_reservation
    ON menu_hold_transition_audits (reservation_id, occurred_at);

CREATE INDEX idx_menu_hold_transition_reservation_hold
    ON menu_hold_transition_audits (reservation_hold_id, occurred_at);

INSERT INTO menu_hold_transition_audits (
    menu_hold_id,
    reservation_id,
    reservation_hold_id,
    event_type,
    before_status,
    after_status,
    result_version,
    occurred_at
)
SELECT menu_hold_id, reservation_id, reservation_hold_id, 'BASELINE', NULL, status, 0, UTC_TIMESTAMP(6)
FROM menu_holds;

DELIMITER $$

CREATE TRIGGER trg_menu_hold_transition_audits_no_update
BEFORE UPDATE ON menu_hold_transition_audits
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'menu hold transition audits are immutable';
END$$

CREATE TRIGGER trg_menu_hold_transition_audits_no_delete
BEFORE DELETE ON menu_hold_transition_audits
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'menu hold transition audits are immutable';
END$$

DELIMITER ;
