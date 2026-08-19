-- Allow a result-unknown refund obligation to remain on bounded automatic reconciliation.
ALTER TABLE reservation_deposit_refund_obligations
    DROP CHECK ck_reservation_deposit_refund_lifecycle,
    ADD CONSTRAINT ck_reservation_deposit_refund_lifecycle
        CHECK (
            (status = 'REQUIRED'
                AND next_attempt_at IS NOT NULL
                AND lease_owner IS NULL
                AND lease_until IS NULL
                AND completed_at IS NULL)
            OR (status = 'PROCESSING'
                AND next_attempt_at IS NULL
                AND CHAR_LENGTH(TRIM(lease_owner)) BETWEEN 1 AND 64
                AND lease_until IS NOT NULL
                AND completed_at IS NULL)
            OR (status = 'RECONCILIATION_REQUIRED'
                AND next_attempt_at IS NOT NULL
                AND lease_owner IS NULL
                AND lease_until IS NULL
                AND completed_at IS NULL)
            OR (status IN ('COMPLETED', 'RECONCILIATION_REQUIRED')
                AND next_attempt_at IS NULL
                AND lease_owner IS NULL
                AND lease_until IS NULL
                AND completed_at IS NOT NULL)
        );
