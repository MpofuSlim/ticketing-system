-- Single-active-session enforcement: each user row carries a monotonically
-- increasing token version (sometimes called a "session epoch"). Every
-- access JWT issued by AuthService stamps the current value as a claim;
-- JwtFilter compares the JWT's claim against the DB on each request and
-- rejects any token whose version is stale. On a fresh /auth/login we
-- bump the column (and revoke all of the user's existing refresh-token
-- families) — that one write invalidates every previously-issued token
-- for this user without needing a per-token denylist entry.
--
-- BIGINT NOT NULL DEFAULT 0 covers a sane upper bound (one login per
-- nanosecond for ~292 years) and gives existing rows a starting value
-- so the Hibernate validate boot doesn't complain about nulls. Tokens
-- minted before this migration ran carried no tokenVersion claim;
-- JwtUtil.extractTokenVersion returns 0 for those, and the new column
-- default is 0, so they pass the check on the FIRST request after the
-- migration runs but are invalidated the next time the user logs in.

ALTER TABLE users
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
