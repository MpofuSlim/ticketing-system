-- tenant_profiles should be one-per-user, mirroring customer_profiles
-- (which already has uk_customer_profiles_user). Without this a user could
-- accumulate multiple tenant profiles. The UNIQUE constraint also creates
-- the index the tenant_profiles.user_id foreign key was missing — deleting
-- a user no longer seq-scans tenant_profiles to enforce the FK.
--
-- If this migration fails on a duplicate, that's a real data bug to clean
-- up first (a user with two business profiles) — the constraint is doing
-- its job by refusing.

ALTER TABLE tenant_profiles
    ADD CONSTRAINT uk_tenant_profiles_user UNIQUE (user_id);
