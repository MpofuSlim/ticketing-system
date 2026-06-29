-- Records the moment ANY notification channel (email / SMS / WhatsApp) confirmed
-- delivery of the temporary password for an approval or admin reset. NULL means
-- nothing reached the user — UserAdminService treats a retried activation on
-- that row as a real retry (re-publishes the credential-delivery event with a
-- fresh temp password) rather than an idempotent no-op.
--
-- Stored as TIMESTAMP WITHOUT TIME ZONE matching the other user.* timestamp
-- columns; the JVM is pinned to UTC (see CLAUDE.md "Timestamps") so values land
-- in UTC regardless of host TZ.
ALTER TABLE users
    ADD COLUMN credential_delivered_at TIMESTAMP NULL;
