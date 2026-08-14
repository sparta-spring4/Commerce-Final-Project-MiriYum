-- Issue #272: Waiting-owned payment-backed reservation conversion lifecycle.
ALTER TABLE waiting_teams
    ADD COLUMN reservation_converting_at DATETIME(6) NULL AFTER closed_by_store_at,
    ADD COLUMN waiting_payment_id VARCHAR(19) NULL AFTER reservation_converting_at,
    ADD COLUMN reservation_reference_id BIGINT NULL AFTER waiting_payment_id,
    ADD COLUMN reservation_converted_at DATETIME(6) NULL AFTER reservation_reference_id;

ALTER TABLE waiting_teams
    DROP CHECK ck_waiting_teams_status,
    DROP CHECK ck_waiting_teams_call_window,
    ADD CONSTRAINT ck_waiting_teams_status
        CHECK (status IN (
            'WAITING',
            'CALLED',
            'ARRIVED',
            'CHECKED_IN',
            'CANCELLED',
            'NO_SHOW',
            'CLOSED_BY_STORE',
            'RESERVATION_CONVERTING',
            'RESERVATION_CONVERTED'
        )),
    ADD CONSTRAINT ck_waiting_teams_call_window
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
                WHEN 'RESERVATION_CONVERTED' THEN
                    called_at IS NULL
                    AND arrival_deadline IS NULL
                    AND arrived_at IS NULL
                    AND checked_in_at IS NULL
                    AND cancelled_at IS NULL
                    AND no_show_at IS NULL
                    AND closed_by_store_at IS NULL
                ELSE FALSE
            END), FALSE) = TRUE
        );

ALTER TABLE waiting_teams
    ADD CONSTRAINT ck_waiting_teams_reservation_conversion
        CHECK (
            COALESCE((CASE status
                WHEN 'RESERVATION_CONVERTING' THEN
                    reservation_reference_id IS NULL
                    AND reservation_converted_at IS NULL
                    AND (
                        (
                            reservation_converting_at IS NULL
                            AND waiting_payment_id IS NULL
                        )
                        OR (
                            reservation_converting_at IS NOT NULL
                            AND reservation_converting_at >= created_at
                            AND REGEXP_LIKE(waiting_payment_id, '^[1-9][0-9]{0,18}$')
                        )
                    )
                WHEN 'RESERVATION_CONVERTED' THEN
                    reservation_converting_at IS NOT NULL
                    AND reservation_converting_at >= created_at
                    AND REGEXP_LIKE(waiting_payment_id, '^[1-9][0-9]{0,18}$')
                    AND reservation_reference_id > 0
                    AND reservation_converted_at >= reservation_converting_at
                WHEN 'CANCELLED' THEN
                    reservation_reference_id IS NULL
                    AND reservation_converted_at IS NULL
                    AND (
                        (
                            reservation_converting_at IS NULL
                            AND waiting_payment_id IS NULL
                        )
                        OR (
                            reservation_converting_at IS NOT NULL
                            AND reservation_converting_at >= created_at
                            AND reservation_converting_at <= cancelled_at
                            AND REGEXP_LIKE(waiting_payment_id, '^[1-9][0-9]{0,18}$')
                        )
                    )
                WHEN 'CLOSED_BY_STORE' THEN
                    reservation_reference_id IS NULL
                    AND reservation_converted_at IS NULL
                    AND (
                        (
                            reservation_converting_at IS NULL
                            AND waiting_payment_id IS NULL
                        )
                        OR (
                            reservation_converting_at IS NOT NULL
                            AND reservation_converting_at >= created_at
                            AND reservation_converting_at <= closed_by_store_at
                            AND REGEXP_LIKE(waiting_payment_id, '^[1-9][0-9]{0,18}$')
                        )
                    )
                ELSE
                    reservation_converting_at IS NULL
                    AND waiting_payment_id IS NULL
                    AND reservation_reference_id IS NULL
                    AND reservation_converted_at IS NULL
            END), FALSE) = TRUE
        );

ALTER TABLE waiting_transition_audits
    DROP CHECK ck_waiting_transition_audits_status,
    ADD CONSTRAINT ck_waiting_transition_audits_status
        CHECK (
            (before_status IS NULL AND after_status = 'WAITING')
            OR (
                before_status IN (
                    'WAITING', 'CALLED', 'ARRIVED', 'CHECKED_IN', 'CANCELLED',
                    'NO_SHOW', 'CLOSED_BY_STORE', 'RESERVATION_CONVERTING',
                    'RESERVATION_CONVERTED'
                )
                AND after_status IN (
                    'WAITING', 'CALLED', 'ARRIVED', 'CHECKED_IN', 'CANCELLED',
                    'NO_SHOW', 'CLOSED_BY_STORE', 'RESERVATION_CONVERTING',
                    'RESERVATION_CONVERTED'
                )
                AND before_status <> after_status
            )
        );

ALTER TABLE waiting_status_events
    DROP CHECK ck_waiting_status_events_public_status,
    ADD CONSTRAINT ck_waiting_status_events_public_status
        CHECK (public_status IN (
            'WAITING',
            'CALLED',
            'ARRIVED',
            'CHECKED_IN',
            'CANCELLED',
            'NO_SHOW',
            'CLOSED_BY_STORE',
            'RESERVATION_CONVERTING',
            'RESERVATION_CONVERTED'
        ));
