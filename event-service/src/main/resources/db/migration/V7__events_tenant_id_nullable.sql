-- Make events.tenant_id nullable so new INSERTs can stop writing it.
-- Application code stopped reading/writing the column in this release —
-- everything keys on tenant_user_uuid now. The column itself stays for
-- one more deploy cycle so {@code TenantUserUuidBackfillRunner} can use
-- it to backfill pre-V6 rows that still have a null tenant_user_uuid.
-- A follow-up migration drops the column entirely after operator confirms
-- zero rows with tenant_user_uuid IS NULL.
--
-- The (tenant_id, title, venue, start_date_time) unique constraint stays
-- in place — every legacy row has a tenant_id value, so it doesn't fire
-- for them; new rows insert tenant_id = null and a NULL UNIQUE column
-- in Postgres treats NULLs as distinct, so the constraint is effectively
-- inactive for new data without being torn down (which is the right
-- order — drop both column AND constraint together in the follow-up).

ALTER TABLE events ALTER COLUMN tenant_id DROP NOT NULL;
