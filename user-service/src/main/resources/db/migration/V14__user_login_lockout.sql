-- Per-account brute-force lockout. After N consecutive wrong passwords
-- (default 7, see innbucks.account-lockout.max-attempts in
-- application.yaml), locked_until is stamped with now()+lockout-duration
-- and the user.login() short-circuits with 423 LOCKED on every attempt
-- until the timestamp passes.
--
-- A successful login resets failed_login_attempts to 0 and clears
-- locked_until, so a legitimate user who mistypes once and then enters
-- the right password doesn't carry the strike forward.
--
-- An expired lockout (locked_until in the past) auto-resets the
-- counter on the next attempt — the user has served the timeout and
-- gets a fresh window. The counter stays cumulative within an active
-- window so parallel attacks against the same account can't outrun the
-- threshold.
--
-- Layered on top of the per-identifier rate limit (5 attempts/min in
-- user-service.LoginRateLimiter) the durability story is:
--   - rate limiter — fast, Redis-backed, in-memory; gone on Redis wipe
--   - account lockout — slow, Postgres-backed; survives Redis outages
--     and IP rotation
--
-- The lockout-triggering 7th wrong password still returns 400 Invalid
-- Credentials (same shape as wrong-pw against a nonexistent account)
-- to avoid an oracle that would let an attacker probe identifier
-- existence cheaply. Subsequent attempts against the locked account
-- return 423 LOCKED — by which point the attacker has already burned
-- 7+ tries to confirm.

ALTER TABLE users
    ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN locked_until TIMESTAMP WITH TIME ZONE;

-- Partial index: only locked rows. Supports any future admin surface
-- that wants to list currently-locked accounts without scanning every
-- user row.
CREATE INDEX IF NOT EXISTS idx_users_locked_until
    ON users(locked_until)
    WHERE locked_until IS NOT NULL;
