-- "Remember this device" — trusted-device 2FA bypass.
--
-- Lets a user who has just passed a step-2 MFA challenge mark THIS device as
-- trusted; on a future login from the same device (same X-Device-Id) presenting
-- the matching trust token, the step-1 MFA challenge is skipped entirely.
--
-- Storage reuses the existing per-(user, device) `devices` table rather than a
-- new table — the unique (user_id, device_id) constraint already gives us the
-- one-row-per-device model trust needs. Two nullable columns are added:
--
--   1) mfa_trust_token_hash — SHA-256 (hex) of the high-entropy trust token we
--      hand the client ONCE at step-2. We store ONLY the hash, mirroring how
--      refresh_tokens stores token_hash — a database leak never exposes a
--      live trust token, and the step-1 check hashes the presented token and
--      compares with a constant-time MessageDigest.isEqual.
--   2) mfa_trusted_until — UTC expiry. Trust is honoured only while this is in
--      the future; default window is MFA_TRUSTED_DEVICE_DAYS (30) days.
--
-- Both nullable: a device with no trust established (the common case) carries
-- NULLs and is never treated as trusted. Trust is revoked by NULLing both
-- columns — done on password change and on MFA disable/reset (security hygiene:
-- a credential change must not leave a standing 2FA bypass behind).

ALTER TABLE devices
    ADD COLUMN mfa_trust_token_hash VARCHAR(255),
    ADD COLUMN mfa_trusted_until    TIMESTAMP WITHOUT TIME ZONE;
