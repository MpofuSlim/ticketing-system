-- Append-only, tamper-evident audit log for money-movement actions in
-- payment-service (OWASP A09). Captures who did what, when, from where, with
-- what outcome — for the payment domain specifically: InnBucks 2D payment-code
-- generation, payment confirmation, payment failure/expiry, unresolvable
-- (UNKNOWN) upstream status, settlement-reconciliation discrepancies, and
-- internal-token trust-boundary failures.
--
-- Used by:
--   - compliance / forensics review ("what happened to booking/payment X
--     between dates Y and Z")
--   - incident response ("did a customer paid-but-unconfirmed row line up with
--     a reconciliation discrepancy?")
--   - regulator reporting ("show me every payment confirmation and failure in
--     the last quarter")
--
-- Append-only by convention — no UPDATE / DELETE from application code. A
-- separate retention job (out of scope) may prune rows past the configured
-- retention window (typically 7 years for financial services).
--
-- Tamper-evidence: unlike user-service (which added row_hmac in a later
-- migration V29 over an existing table), payment-service starts fresh with no
-- legacy rows, so the row_hmac column is created here in the SAME migration as
-- the table. On write, AuditService computes
--   row_hmac = HMAC-SHA256(audit.hmac-secret, canonical(row fields))
-- over the immutable columns. The secret lives in the app config / env (guarded
-- by ProductionSecretsGuard), NOT in the database — so an attacker with only DB
-- write access cannot forge a matching HMAC after altering a row. The
-- AuditIntegrityVerifier recomputes and compares, emitting
-- payment.audit.integrity.broken for any mismatch. VARCHAR(64) holds the
-- hex-encoded SHA-256 (32 bytes -> 64 hex chars); VARCHAR (not CHAR) to match
-- the entity's String @Column mapping under Hibernate ddl-auto: validate.
--
-- The metadata column is TEXT carrying serialised JSON (not JSONB) because H2 in
-- the test profile doesn't support JSONB and the Hibernate schema-validation
-- path would refuse to start. TEXT gives us the same flexibility at the cost of
-- no native JSON indexing — acceptable for an append-only audit table where the
-- common query shapes (by actor, by event_type, by target, by occurred_at) are
-- covered by dedicated columns.

CREATE TABLE audit_events (
    id              BIGSERIAL PRIMARY KEY,
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL,

    -- PAYMENT_CODE_GENERATED, PAYMENT_CODE_GENERATION_FAILED,
    -- PAYMENT_CONFIRMED, PAYMENT_FAILED, PAYMENT_STATUS_UNKNOWN,
    -- PAYMENT_RECON_DISCREPANCY, PAYMENT_INTERNAL_TOKEN_FAILURE, ...
    -- Free-form for now; an enum table is overkill at this size.
    event_type      VARCHAR(64) NOT NULL,

    -- Identifier of the principal performing the action. Usually a user id for
    -- an authenticated actor, "anonymous" for an unauthenticated attempt,
    -- "system" for background jobs (reconciliation, code-status polling, ...).
    actor_id        VARCHAR(64),
    actor_type      VARCHAR(32),

    -- Identifier of the resource acted upon (typically the booking id or the
    -- payment / ledger row the money-movement event concerns).
    target_id       VARCHAR(64),
    target_type     VARCHAR(32),

    -- Network context. ip_address is X-Forwarded-For-aware. Tunable column
    -- lengths above the IPv6 max so we never lose audit fidelity to truncation.
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(512),

    -- "SUCCESS" or "FAILURE". Required so the common "show me only failures"
    -- query is an index seek, not a table scan filter.
    outcome         VARCHAR(16) NOT NULL,

    -- Human-readable explanation for FAILURE rows. Same shape across callers so
    -- dashboards can group consistently.
    failure_reason  VARCHAR(255),

    -- Trace / correlation ID for joining to OTel spans + access logs.
    -- Populated from MDC when present.
    correlation_id  VARCHAR(64),

    -- Free-form structured payload. Serialised JSON. Stays small — callers
    -- should pass scalar key/value pairs, not blobs.
    metadata        TEXT,

    -- OWASP A09 tamper-evidence tag. See header. Nullable so a row that somehow
    -- predates the seal (or a future migration path) is reported as
    -- "legacy/unverifiable" (distinct from "tampered") by the verifier.
    row_hmac        VARCHAR(64)
);

-- Common query: "everything for target X, newest first". The DESC sort
-- direction matches what every UI / dashboard wants by default.
-- Partial index trims rows with NULL actor_id (anonymous attempts);
-- those are still queryable via the event_type index below.
CREATE INDEX IF NOT EXISTS idx_audit_events_actor_id
    ON audit_events(actor_id, occurred_at DESC)
    WHERE actor_id IS NOT NULL;

-- "Show me all payment failures in the last hour" style queries.
CREATE INDEX IF NOT EXISTS idx_audit_events_event_type
    ON audit_events(event_type, occurred_at DESC);

-- "Everything that happened to booking / payment Y" — support flows.
CREATE INDEX IF NOT EXISTS idx_audit_events_target_id
    ON audit_events(target_id, occurred_at DESC)
    WHERE target_id IS NOT NULL;
