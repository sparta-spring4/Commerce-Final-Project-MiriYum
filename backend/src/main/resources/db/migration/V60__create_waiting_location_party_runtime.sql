-- Issue #409: GPS-bound one-time registration proofs and consumer party runtime.

ALTER TABLE waiting_active_memberships
    ADD INDEX idx_waiting_active_memberships_team (waiting_team_id),
    DROP INDEX uk_waiting_active_memberships_team;

CREATE TABLE waiting_location_proof_sessions (
    location_proof_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consumer_account_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    result_category VARCHAR(32) NOT NULL,
    accuracy_category VARCHAR(32) NOT NULL,
    policy_version VARCHAR(50) NOT NULL,
    store_coordinate_version BIGINT NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    judged_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    consumed_waiting_team_id BIGINT NULL,
    PRIMARY KEY (location_proof_session_id),
    CONSTRAINT fk_waiting_location_proofs_consumer
        FOREIGN KEY (consumer_account_id)
        REFERENCES consumer_accounts (consumer_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_waiting_location_proofs_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_waiting_location_proofs_consumed_team
        FOREIGN KEY (consumed_waiting_team_id)
        REFERENCES waiting_teams (waiting_team_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_waiting_location_proofs_purpose
        CHECK (purpose IN ('WAITING_REGISTRATION')),
    CONSTRAINT ck_waiting_location_proofs_result
        CHECK (result_category IN (
            'VERIFIED', 'OUTSIDE_RADIUS', 'ACCURACY_INSUFFICIENT',
            'PERMISSION_DENIED', 'MEASUREMENT_STALE', 'POSITION_UNAVAILABLE',
            'MANIPULATION_SUSPECTED'
        )),
    CONSTRAINT ck_waiting_location_proofs_accuracy
        CHECK (accuracy_category IN ('ACCEPTABLE', 'INSUFFICIENT', 'NOT_APPLICABLE')),
    CONSTRAINT ck_waiting_location_proofs_versions
        CHECK (
            CHAR_LENGTH(TRIM(policy_version)) BETWEEN 1 AND 50
            AND store_coordinate_version >= 0
        ),
    CONSTRAINT ck_waiting_location_proofs_time
        CHECK (issued_at = judged_at AND judged_at < expires_at),
    CONSTRAINT ck_waiting_location_proofs_consumption
        CHECK (
            (consumed_at IS NULL AND consumed_waiting_team_id IS NULL)
            OR (
                consumed_at IS NOT NULL
                AND consumed_waiting_team_id IS NOT NULL
                AND consumed_at BETWEEN judged_at AND expires_at
            )
        ),
    INDEX idx_waiting_location_proofs_binding (
        consumer_account_id, store_id, purpose, expires_at
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE waiting_party_invitations (
    waiting_party_invitation_id BIGINT NOT NULL AUTO_INCREMENT,
    waiting_team_id BIGINT NOT NULL,
    inviter_consumer_account_id BIGINT NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issued_team_version BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    accepted_by_consumer_account_id BIGINT NULL,
    accepted_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (waiting_party_invitation_id),
    CONSTRAINT uk_waiting_party_invitations_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_waiting_party_invitations_team
        FOREIGN KEY (waiting_team_id)
        REFERENCES waiting_teams (waiting_team_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_waiting_party_invitations_inviter
        FOREIGN KEY (inviter_consumer_account_id)
        REFERENCES consumer_accounts (consumer_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_waiting_party_invitations_acceptor
        FOREIGN KEY (accepted_by_consumer_account_id)
        REFERENCES consumer_accounts (consumer_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_waiting_party_invitations_status
        CHECK (status IN ('ISSUED', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_waiting_party_invitations_version
        CHECK (issued_team_version >= 0),
    CONSTRAINT ck_waiting_party_invitations_time
        CHECK (issued_at < expires_at),
    CONSTRAINT ck_waiting_party_invitations_decision
        CHECK (
            (status = 'ISSUED' AND accepted_by_consumer_account_id IS NULL
                AND accepted_at IS NULL AND revoked_at IS NULL)
            OR (status = 'ACCEPTED' AND accepted_by_consumer_account_id IS NOT NULL
                AND accepted_at BETWEEN issued_at AND expires_at AND revoked_at IS NULL)
            OR (status = 'REVOKED' AND accepted_by_consumer_account_id IS NULL
                AND accepted_at IS NULL AND revoked_at >= issued_at)
            OR (status = 'EXPIRED' AND accepted_by_consumer_account_id IS NULL
                AND accepted_at IS NULL AND revoked_at IS NULL)
        ),
    INDEX idx_waiting_party_invitations_team_status (
        waiting_team_id, status, waiting_party_invitation_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE waiting_representative_transfer_offers (
    waiting_representative_transfer_offer_id BIGINT NOT NULL AUTO_INCREMENT,
    waiting_team_id BIGINT NOT NULL,
    from_consumer_account_id BIGINT NOT NULL,
    target_membership_id BIGINT NOT NULL,
    proposed_team_version BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    proposed_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    decided_at DATETIME(6) NULL,
    active_team_key BIGINT NULL,
    PRIMARY KEY (waiting_representative_transfer_offer_id),
    CONSTRAINT uk_waiting_transfer_active_team UNIQUE (active_team_key),
    CONSTRAINT fk_waiting_transfer_offers_team
        FOREIGN KEY (waiting_team_id)
        REFERENCES waiting_teams (waiting_team_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_waiting_transfer_offers_from_consumer
        FOREIGN KEY (from_consumer_account_id)
        REFERENCES consumer_accounts (consumer_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_waiting_transfer_offers_status
        CHECK (status IN ('PROPOSED', 'ACCEPTED', 'REJECTED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_waiting_transfer_offers_version
        CHECK (proposed_team_version >= 0),
    CONSTRAINT ck_waiting_transfer_offers_time
        CHECK (proposed_at < expires_at),
    CONSTRAINT ck_waiting_transfer_offers_decision
        CHECK (
            (status = 'PROPOSED' AND decided_at IS NULL AND active_team_key = waiting_team_id)
            OR (status <> 'PROPOSED' AND decided_at IS NOT NULL AND active_team_key IS NULL)
        ),
    INDEX idx_waiting_transfer_offers_team_status (
        waiting_team_id, status, waiting_representative_transfer_offer_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE waiting_party_audits (
    waiting_party_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    waiting_team_id BIGINT NOT NULL,
    actor_consumer_account_id BIGINT NOT NULL,
    subject_membership_id BIGINT NULL,
    event_type VARCHAR(48) NOT NULL,
    before_team_version BIGINT NOT NULL,
    after_team_version BIGINT NOT NULL,
    reason VARCHAR(255) NOT NULL,
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (waiting_party_audit_id),
    CONSTRAINT uk_waiting_party_audits_command UNIQUE (command_id),
    CONSTRAINT fk_waiting_party_audits_team
        FOREIGN KEY (waiting_team_id)
        REFERENCES waiting_teams (waiting_team_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_waiting_party_audits_actor
        FOREIGN KEY (actor_consumer_account_id)
        REFERENCES consumer_accounts (consumer_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_waiting_party_audits_event
        CHECK (event_type IN (
            'INVITATION_ISSUED', 'INVITATION_REVOKED', 'MEMBER_JOINED',
            'MEMBER_DEPARTED', 'MEMBER_REMOVED', 'REPRESENTATIVE_TRANSFER_PROPOSED',
            'REPRESENTATIVE_TRANSFER_ACCEPTED', 'REPRESENTATIVE_TRANSFER_REJECTED',
            'REPRESENTATIVE_TRANSFER_REVOKED', 'REPRESENTATIVE_TRANSFER_EXPIRED'
        )),
    CONSTRAINT ck_waiting_party_audits_versions
        CHECK (
            before_team_version >= 0
            AND after_team_version >= before_team_version
            AND after_team_version <= before_team_version + 1
        ),
    CONSTRAINT ck_waiting_party_audits_reason
        CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 255),
    INDEX idx_waiting_party_audits_team (
        waiting_team_id, waiting_party_audit_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
