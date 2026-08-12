-- Issue #272: central waiting ledger, active duplicate lock, FIFO allocation and close jobs.
CREATE TABLE waiting_queue_sequences (
    store_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    next_sequence BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (store_id, business_date),
    CONSTRAINT fk_waiting_queue_sequences_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_waiting_queue_sequences_next
        CHECK (next_sequence > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE waiting_teams (
    waiting_team_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    consumer_account_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    party_size INT NOT NULL,
    source VARCHAR(16) NOT NULL,
    queue_sequence BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    called_at DATETIME(6) NULL,
    arrival_deadline DATETIME(6) NULL,
    arrived_at DATETIME(6) NULL,
    checked_in_at DATETIME(6) NULL,
    cancelled_at DATETIME(6) NULL,
    no_show_at DATETIME(6) NULL,
    closed_by_store_at DATETIME(6) NULL,
    PRIMARY KEY (waiting_team_id),
    CONSTRAINT uk_waiting_teams_store_date_sequence
        UNIQUE (store_id, business_date, queue_sequence),
    CONSTRAINT fk_waiting_teams_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_waiting_teams_consumer_account
        FOREIGN KEY (consumer_account_id)
        REFERENCES consumer_accounts (consumer_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_waiting_teams_party_size
        CHECK (party_size > 0),
    CONSTRAINT ck_waiting_teams_source
        CHECK (source IN ('REMOTE', 'ON_SITE')),
    CONSTRAINT ck_waiting_teams_queue_sequence
        CHECK (queue_sequence > 0),
    CONSTRAINT ck_waiting_teams_status
        CHECK (status IN (
            'WAITING',
            'CALLED',
            'ARRIVED',
            'CHECKED_IN',
            'CANCELLED',
            'NO_SHOW',
            'CLOSED_BY_STORE',
            'RESERVATION_CONVERTING'
        )),
    CONSTRAINT ck_waiting_teams_version
        CHECK (version >= 0),
    CONSTRAINT ck_waiting_teams_call_window
        CHECK (
            COALESCE((CASE status
                WHEN 'WAITING' THEN
                    called_at IS NULL
                    AND arrival_deadline IS NULL
                    AND arrived_at IS NULL
                    AND checked_in_at IS NULL
                    AND cancelled_at IS NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NULL
                WHEN 'CALLED' THEN
                    called_at IS NOT NULL
                    AND called_at >= created_at
                    AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                    AND arrived_at IS NULL
                    AND checked_in_at IS NULL
                    AND cancelled_at IS NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NULL
                WHEN 'ARRIVED' THEN
                    called_at IS NOT NULL
                    AND called_at >= created_at
                    AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                    AND arrived_at BETWEEN called_at AND arrival_deadline
                    AND checked_in_at IS NULL
                    AND cancelled_at IS NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NULL
                WHEN 'CHECKED_IN' THEN
                    called_at IS NOT NULL
                    AND called_at >= created_at
                    AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                    AND arrived_at BETWEEN called_at AND arrival_deadline
                    AND checked_in_at >= arrived_at
                    AND cancelled_at IS NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NULL
                WHEN 'CANCELLED' THEN
                    checked_in_at IS NULL
                    AND cancelled_at IS NOT NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NULL
                    AND (
                        (
                            called_at IS NULL
                            AND arrival_deadline IS NULL
                            AND arrived_at IS NULL
                            AND cancelled_at >= created_at
                        )
                        OR (
                            called_at IS NOT NULL
                            AND called_at >= created_at
                            AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                            AND arrived_at IS NULL
                            AND cancelled_at >= called_at
                        )
                        OR (
                            called_at IS NOT NULL
                            AND called_at >= created_at
                            AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                            AND arrived_at BETWEEN called_at AND arrival_deadline
                            AND cancelled_at >= arrived_at
                        )
                    )
                WHEN 'NO_SHOW' THEN
                    called_at IS NOT NULL
                    AND called_at >= created_at
                    AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                    AND arrived_at IS NULL
                    AND checked_in_at IS NULL
                    AND cancelled_at IS NULL
                    AND no_show_at >= arrival_deadline
                    AND closed_by_store_at IS NULL
                WHEN 'CLOSED_BY_STORE' THEN
                    checked_in_at IS NULL
                    AND cancelled_at IS NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NOT NULL
                    AND (
                        (
                            called_at IS NULL
                            AND arrival_deadline IS NULL
                            AND arrived_at IS NULL
                            AND closed_by_store_at >= created_at
                        )
                        OR (
                            called_at IS NOT NULL
                            AND called_at >= created_at
                            AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                            AND arrived_at IS NULL
                            AND closed_by_store_at >= called_at
                        )
                        OR (
                            called_at IS NOT NULL
                            AND called_at >= created_at
                            AND arrival_deadline = TIMESTAMPADD(MINUTE, 10, called_at)
                            AND arrived_at BETWEEN called_at AND arrival_deadline
                            AND closed_by_store_at >= arrived_at
                        )
                    )
                WHEN 'RESERVATION_CONVERTING' THEN
                    called_at IS NULL
                    AND arrival_deadline IS NULL
                    AND arrived_at IS NULL
                    AND checked_in_at IS NULL
                    AND cancelled_at IS NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NULL
                ELSE FALSE
            END), FALSE) = TRUE
        ),
    INDEX idx_waiting_teams_fifo (
        store_id,
        business_date,
        status,
        queue_sequence,
        waiting_team_id
    ),
    INDEX idx_waiting_teams_consumer_history (
        consumer_account_id,
        created_at,
        waiting_team_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE waiting_active_memberships (
    waiting_active_membership_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    consumer_account_id BIGINT NOT NULL,
    waiting_team_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (waiting_active_membership_id),
    CONSTRAINT uk_waiting_active_memberships_store_consumer
        UNIQUE (store_id, consumer_account_id),
    CONSTRAINT uk_waiting_active_memberships_team
        UNIQUE (waiting_team_id),
    CONSTRAINT fk_waiting_active_memberships_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_waiting_active_memberships_consumer_account
        FOREIGN KEY (consumer_account_id)
        REFERENCES consumer_accounts (consumer_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_waiting_active_memberships_team
        FOREIGN KEY (waiting_team_id)
        REFERENCES waiting_teams (waiting_team_id)
        ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE waiting_transition_audits (
    waiting_transition_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    waiting_team_id BIGINT NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_id BIGINT NULL,
    before_status VARCHAR(32) NULL,
    after_status VARCHAR(32) NOT NULL,
    expected_version BIGINT NOT NULL,
    result_version BIGINT NOT NULL,
    reason VARCHAR(255) NOT NULL,
    command_id VARCHAR(100) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (waiting_transition_audit_id),
    CONSTRAINT uk_waiting_transition_audits_command
        UNIQUE (command_id),
    CONSTRAINT fk_waiting_transition_audits_team
        FOREIGN KEY (waiting_team_id)
        REFERENCES waiting_teams (waiting_team_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_waiting_transition_audits_actor
        CHECK (
            actor_type IN ('CONSUMER', 'STORE_OPERATOR', 'SYSTEM')
            AND (
                (actor_type = 'SYSTEM' AND (actor_id IS NULL OR actor_id > 0))
                OR (actor_type <> 'SYSTEM' AND actor_id IS NOT NULL AND actor_id > 0)
            )
        ),
    CONSTRAINT ck_waiting_transition_audits_status
        CHECK (
            (before_status IS NULL AND after_status = 'WAITING')
            OR (
                before_status IN (
                    'WAITING', 'CALLED', 'ARRIVED', 'CHECKED_IN', 'CANCELLED',
                    'NO_SHOW', 'CLOSED_BY_STORE', 'RESERVATION_CONVERTING'
                )
                AND after_status IN (
                    'WAITING', 'CALLED', 'ARRIVED', 'CHECKED_IN', 'CANCELLED',
                    'NO_SHOW', 'CLOSED_BY_STORE', 'RESERVATION_CONVERTING'
                )
                AND before_status <> after_status
            )
        ),
    CONSTRAINT ck_waiting_transition_audits_versions
        CHECK (
            (
                before_status IS NULL
                AND after_status = 'WAITING'
                AND expected_version = -1
                AND result_version = 0
            )
            OR (
                before_status IS NOT NULL
                AND expected_version >= 0
                AND result_version = expected_version + 1
            )
        ),
    CONSTRAINT ck_waiting_transition_audits_command
        CHECK (CHAR_LENGTH(TRIM(command_id)) BETWEEN 1 AND 100),
    CONSTRAINT ck_waiting_transition_audits_reason
        CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 255),
    CONSTRAINT ck_waiting_transition_audits_time
        CHECK (occurred_at <= created_at),
    INDEX idx_waiting_transition_audits_team (
        waiting_team_id,
        waiting_transition_audit_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE waiting_closure_jobs (
    waiting_closure_job_id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    settings_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    target_team_count BIGINT NOT NULL DEFAULT 0,
    completed_team_count BIGINT NOT NULL DEFAULT 0,
    failed_team_count BIGINT NOT NULL DEFAULT 0,
    reconciliation_required_team_count BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (waiting_closure_job_id),
    CONSTRAINT uk_waiting_closure_jobs_store_settings
        UNIQUE (store_id, settings_version),
    CONSTRAINT fk_waiting_closure_jobs_store
        FOREIGN KEY (store_id)
        REFERENCES stores (store_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_waiting_closure_jobs_settings_version
        CHECK (settings_version >= 0),
    CONSTRAINT ck_waiting_closure_jobs_status
        CHECK (status IN (
            'PENDING',
            'PROCESSING',
            'COMPLETED',
            'RECONCILIATION_REQUIRED'
        )),
    CONSTRAINT ck_waiting_closure_jobs_counts
        CHECK (
            target_team_count >= 0
            AND completed_team_count >= 0
            AND failed_team_count >= 0
            AND reconciliation_required_team_count >= 0
            AND completed_team_count + failed_team_count
                + reconciliation_required_team_count <= target_team_count
        ),
    CONSTRAINT ck_waiting_closure_jobs_version
        CHECK (version >= 0),
    CONSTRAINT ck_waiting_closure_jobs_completion
        CHECK (
            (status = 'COMPLETED' AND completed_at IS NOT NULL)
            OR (status <> 'COMPLETED')
        ),
    INDEX idx_waiting_closure_jobs_worker (
        status,
        created_at,
        waiting_closure_job_id
    ),
    INDEX idx_waiting_closure_jobs_store (
        store_id,
        created_at,
        waiting_closure_job_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE waiting_closure_job_items (
    waiting_closure_job_item_id BIGINT NOT NULL AUTO_INCREMENT,
    waiting_closure_job_id BIGINT NOT NULL,
    waiting_team_id BIGINT NOT NULL,
    expected_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_attempted_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (waiting_closure_job_item_id),
    CONSTRAINT uk_waiting_closure_job_items_job_team
        UNIQUE (waiting_closure_job_id, waiting_team_id),
    CONSTRAINT fk_waiting_closure_job_items_job
        FOREIGN KEY (waiting_closure_job_id)
        REFERENCES waiting_closure_jobs (waiting_closure_job_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_waiting_closure_job_items_team
        FOREIGN KEY (waiting_team_id)
        REFERENCES waiting_teams (waiting_team_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_waiting_closure_job_items_status
        CHECK (status IN (
            'PENDING',
            'PROCESSING',
            'COMPLETED',
            'FAILED',
            'RECONCILIATION_REQUIRED'
        )),
    CONSTRAINT ck_waiting_closure_job_items_version
        CHECK (expected_version >= 0),
    CONSTRAINT ck_waiting_closure_job_items_attempts
        CHECK (attempt_count >= 0),
    INDEX idx_waiting_closure_job_items_claim (
        waiting_closure_job_id,
        status,
        waiting_closure_job_item_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE waiting_status_events (
    waiting_status_event_id BIGINT NOT NULL AUTO_INCREMENT,
    waiting_team_id BIGINT NOT NULL,
    event_sequence BIGINT NOT NULL,
    public_status VARCHAR(32) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    publication_state VARCHAR(16) NOT NULL,
    PRIMARY KEY (waiting_status_event_id),
    CONSTRAINT uk_waiting_status_events_team_sequence
        UNIQUE (waiting_team_id, event_sequence),
    CONSTRAINT fk_waiting_status_events_team
        FOREIGN KEY (waiting_team_id)
        REFERENCES waiting_teams (waiting_team_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_waiting_status_events_sequence
        CHECK (event_sequence > 0),
    CONSTRAINT ck_waiting_status_events_public_status
        CHECK (public_status IN (
            'WAITING',
            'CALLED',
            'ARRIVED',
            'CHECKED_IN',
            'CANCELLED',
            'NO_SHOW',
            'CLOSED_BY_STORE',
            'RESERVATION_CONVERTING'
        )),
    CONSTRAINT ck_waiting_status_events_publication
        CHECK (publication_state IN ('PENDING', 'PUBLISHED')),
    INDEX idx_waiting_status_events_publication (
        publication_state,
        waiting_status_event_id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
