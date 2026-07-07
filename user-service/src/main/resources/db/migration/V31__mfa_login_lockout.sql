-- OWASP A04/A07: dedicated brute-force lockout for the MFA login step
-- (POST /auth/login/mfa — TOTP / backup-code verification).
--
-- These are intentionally SEPARATE from failed_login_attempts / locked_until
-- (V14, the password step). The password path resets failed_login_attempts to 0
-- on every successful password check, so if the MFA step reused those columns an
-- attacker who ALREADY holds the password could wipe their accumulated MFA
-- strikes just by re-authenticating (login step 1 succeeds -> counter reset ->
-- another batch of TOTP guesses), never tripping the cap. mfa_failed_attempts is
-- only ever touched by the MFA verification path, so the lockout survives
-- re-login and the 6-digit TOTP can't be ground down.
--
-- On the Nth (= mfa.lockout threshold) wrong code the account's MFA step is
-- locked until mfa_locked_until; while locked, /auth/login/mfa returns 423 LOCKED
-- (same response shape as the password lockout) regardless of a freshly minted
-- mfaToken. A successful code clears both columns.
ALTER TABLE users
    ADD COLUMN mfa_failed_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN mfa_locked_until    TIMESTAMP WITH TIME ZONE;

-- Partial index mirrors idx_users_locked_until (V14): only the small set of
-- currently-locked rows is indexed, for cheap reaper/observability scans.
CREATE INDEX IF NOT EXISTS idx_users_mfa_locked_until
    ON users(mfa_locked_until)
    WHERE mfa_locked_until IS NOT NULL;
