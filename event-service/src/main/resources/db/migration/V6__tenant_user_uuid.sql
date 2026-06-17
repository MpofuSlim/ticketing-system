-- Stable cross-service organizer reference. tenant_id (VARCHAR) stays in
-- place — it's the legacy email pointer the FE and the loyalty pipeline
-- still read — but tenant_user_uuid is the durable identifier going
-- forward (matches users.user_uuid; immune to the organizer editing their
-- email or any other profile-mutating refactor).
--
-- Nullable for the transition: rows created before this migration land
-- (or before the backfill runner finishes) keep a null in this column
-- and continue to be served via tenant_id. Once the FE has migrated and
-- the backfill is complete, a later migration will flip this NOT NULL
-- and drop tenant_id. EventService writes both columns on every new
-- INSERT (dual-write) so the column never goes stale for new rows.

ALTER TABLE events
    ADD COLUMN tenant_user_uuid UUID;

CREATE INDEX idx_events_tenant_user_uuid
    ON events(tenant_user_uuid)
    WHERE tenant_user_uuid IS NOT NULL;
