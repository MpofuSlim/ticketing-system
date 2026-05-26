-- System-user registration becomes approval-gated.
--
-- /auth/register no longer accepts a password. New system users are created
-- inactive and unapproved, with an unusable placeholder password. The first
-- SUPER_ADMIN activation approves the account, assigns the shared default
-- password (#Pass123) and flags must_change_password so the user is forced to
-- rotate it on first login.
--
-- New columns:
--   users.country               personal/account country (all users)
--   users.is_business           business-account flag (drives TenantProfile)
--   users.approved              approval gate (see above)
--   users.must_change_password  forces a first-login password change
--   tenant_profiles.bpo_number  business BPO number (business accounts)

ALTER TABLE users ADD COLUMN country VARCHAR(255);
ALTER TABLE users ADD COLUMN is_business BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN approved BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

-- Grandfather every pre-existing account as already-approved so a later
-- activation toggle never overwrites a real user's password with the default.
UPDATE users SET approved = TRUE;

ALTER TABLE tenant_profiles ADD COLUMN bpo_number VARCHAR(255);
