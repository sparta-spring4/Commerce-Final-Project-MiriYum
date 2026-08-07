ALTER TABLE consumer_accounts
    ADD COLUMN reservation_contact_reference VARCHAR(512) NULL;

ALTER TABLE consumer_accounts
    ADD CONSTRAINT uk_consumer_accounts_reservation_contact_reference
        UNIQUE (reservation_contact_reference);
