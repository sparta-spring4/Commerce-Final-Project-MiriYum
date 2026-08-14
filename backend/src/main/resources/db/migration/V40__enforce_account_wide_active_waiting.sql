-- Issue #272 / WAIT-008: replace the V36 store-scoped active membership key with one per account.
--
-- Deployment gate (run and record this limited operational report before applying V40):
-- SELECT consumer_account_id, COUNT(*) AS active_membership_count
-- FROM waiting_active_memberships
-- GROUP BY consumer_account_id
-- HAVING COUNT(*) > 1;
--
-- If the report has any row, BLOCK this migration and deployment. Do not delete, merge, or
-- auto-cancel memberships. The Waiting owner must inspect the ledger and audit trail, complete
-- the valid public closure transition(s), record the operator and basis without exposing account
-- identifiers in user responses or general logs, and rerun the report until it returns zero rows.
-- Only then may this migration replace the V36 constraint. Any failure is a blocked deployment,
-- not a successful migration.

-- V36's composite unique key is also MySQL's supporting index for the store FK.
-- Preserve that FK support before removing the obsolete uniqueness rule.
-- MySQL 8 executes this multi-action ALTER atomically: if the required account-wide UNIQUE
-- cannot be added because the pre-reconciliation report is non-empty, data and V36's key stay intact.
ALTER TABLE waiting_active_memberships
    ADD INDEX idx_waiting_active_memberships_store (store_id),
    ADD CONSTRAINT uk_waiting_active_memberships_consumer_account
        UNIQUE (consumer_account_id),
    DROP INDEX uk_waiting_active_memberships_store_consumer;
