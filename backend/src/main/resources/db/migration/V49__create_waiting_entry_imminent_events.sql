CREATE TABLE waiting_entry_imminent_events (
    waiting_entry_imminent_event_id BIGINT NOT NULL AUTO_INCREMENT,
    waiting_team_id BIGINT NOT NULL,
    event_sequence BIGINT NOT NULL,
    occurred_at DATETIME(0) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (waiting_entry_imminent_event_id),
    CONSTRAINT fk_waiting_entry_imminent_events_team
        FOREIGN KEY (waiting_team_id) REFERENCES waiting_teams (waiting_team_id),
    CONSTRAINT uk_waiting_entry_imminent_events_team UNIQUE (waiting_team_id),
    CONSTRAINT ck_waiting_entry_imminent_events_sequence CHECK (event_sequence > 0)
) ENGINE = InnoDB;
