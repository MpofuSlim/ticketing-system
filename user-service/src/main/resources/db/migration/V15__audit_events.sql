-- Append-only audit log for security-sensitive actions in user-service.
-- Captures who did what, when, from where, with what outcome.
--
-- Used by:
--   - compliance / forensics review ("what happened on account X
--     between dates Y and Z")
--   - incident response ("did the attacker who locked this account
--     also try to refresh tokens?")
--   - regulator reporting ("show me every successful and failed login
--     in the last quarter")
--
-- Append-only by convention — no UPDATE / DELETE from application
-- code. A separate retention job (out of scope) may prune rows past
-- the configured retention window (typically 7 years for financial
-- services).
--
-- The metadata column is TEXT carrying serialised JSON (not JSONB)
-- because H2 in the test profile doesn't support JSONB and the
-- Hibernate schema-validation path would refuse to start. TEXT
-- gives us the same flexibility at the cost of no native JSON
-- indexing — acceptable for an append-only audit table where the
-- common query shapes (by actor, by event_type, by target, by
-- occurred_at) are covered by dedicated columns.
--
-- Storage note: row volume scales with login traffic. At 100 logins/
-- second sustained that's 8.6M rows/day. The id BIGSERIAL handles
-- ~9.2e18 rows so we're fine on that axis; the indexes below are
-- the cost centre. Watch idx_audit_events_actor_id growth — if a
-- handful of bots dominate the actor_id distribution, consider a
-- BRIN index on occurred_at instead.

CREATE TABLE audit_events (
    id              BIGSERIAL PRIMARY KEY,
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL,

    -- AUTH_LOGIN_SUCCESS, AUTH_LOGIN_FAILURE, AUTH_ACCOUNT_LOCKED,
    -- AUTH_LOGOUT, AUTH_REFRESH_SUCCESS, AUTH_REFRESH_REUSE_DETECTED,
    -- AUTH_REFRESH_DEVICE_MISMATCH, AUTH_PASSWORD_CHANGED, ...
    -- Free-form for now; an enum table is overkill at this size.
    event_type      VARCHAR(64) NOT NULL,

    -- Identifier of the principal performing the action. Usually
    -- users.id for an authenticated actor, "anonymous" for an
    -- unauthenticated /auth/login attempt, "system" for background
    -- jobs (reconciliation, OTP expiry, ...).
    actor_id        VARCHAR(64),
    actor_type      VARCHAR(32),

    -- Identifier of the resource acted upon. Often the same as
    -- actor_id for self-targeted actions (login, password change);
    -- different for admin-on-other-user actions.
    target_id       VARCHAR(64),
    target_type     VARCHAR(32),

    -- Network context. ip_address is X-Forwarded-For-aware (set by
    -- AuthController.clientIp). Tunable column lengths above the
    -- IPv6 max so we never lose audit fidelity to truncation.
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(512),

    -- "SUCCESS" or "FAILURE". Required so the common "show me only
    -- failures" query is an index seek, not a table scan filter.
    outcome         VARCHAR(16) NOT NULL,

    -- Human-readable explanation for FAILURE rows. Same shape across
    -- callers so dashboards can group consistently.
    failure_reason  VARCHAR(255),

    -- Trace / correlation ID for joining to OTel spans + access logs.
    -- Populated from MDC when present.
    correlation_id  VARCHAR(64),

    -- Free-form structured payload. Serialised JSON. Stays small —
    -- callers should pass scalar key/value pairs, not blobs.
    metadata        TEXT
);

-- Common query: "everything for user X, newest first". The DESC sort
-- direction matches what every UI / dashboard wants by default.
-- Partial index trims rows with NULL actor_id (anonymous attempts);
-- those are still queryable via the event_type index below.
CREATE INDEX IF NOT EXISTS idx_audit_events_actor_id
    ON audit_events(actor_id, occurred_at DESC)
    WHERE actor_id IS NOT NULL;

-- "Show me all failed logins in the last hour" style queries.
CREATE INDEX IF NOT EXISTS idx_audit_events_event_type
    ON audit_events(event_type, occurred_at DESC);

-- "Everything that happened to transaction Y" — used by
-- payment-service support flows once they log against this table
-- (or its sibling in their own DB).
CREATE INDEX IF NOT EXISTS idx_audit_events_target_id
    ON audit_events(target_id, occurred_at DESC)
    WHERE target_id IS NOT NULL;
