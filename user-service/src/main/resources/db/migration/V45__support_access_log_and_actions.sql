-- Unified customer support (Ask C, PR 2): the read log every lookup writes and
-- the idempotency record every support write keys on.
--
-- Additive: two new tables, nothing existing is touched, so the previous image
-- boots against this schema (Hibernate validate ignores tables it does not map).

SET LOCAL lock_timeout = '5s';

-- One row per support call that reached a customer: every search (POST
-- /admin/support/customers/search), every section detail read and every support
-- write, plus the call-center reads of GET /admin/device-security/**.
--
-- Deliberately NOT the tamper-evident audit_events chain (D10): the chain
-- serialises every writer on audit_chain_head's row lock, and a lookup is the
-- highest-volume thing an agent does. Writes ARE sealed on the chain as well
-- (SUPPORT_* events); this table is the record of who LOOKED at whom. An hourly
-- chain seal over each hour's rows (so a deleted row is detectable) is deferred
-- until user-service has a scheduler lock.
--
-- customer_keys holds the lookup's RESOLVED customer keys in full (phones,
-- emails, user uuids and ids, a reference) as serialised JSON: every later
-- detail or write call is bound to them (the target must be one of them), and
-- user-service already holds each value in users/customer_profiles. query_masked
-- is what the agent typed, masked; a REFUSED query stores its kind only, never
-- its text. client_ip is whatever the edge reported and is labelled untrusted:
-- the gateway forwards the caller-controlled left-most X-Forwarded-For.
--
-- Kept 12 months (SupportAccessLogRetentionJob).
CREATE TABLE support_access_log (
    id                   BIGSERIAL     PRIMARY KEY,
    created_at           TIMESTAMP     NOT NULL,
    -- SLK-XXXXXX. The lookup a SEARCH row issued, or the lookup a detail/write
    -- call was bound to; NULL for a refused search and for device-security reads.
    lookup_id            VARCHAR(16),
    op                   VARCHAR(64)   NOT NULL,
    -- OK, REPLAYED, or the refusal's errorCode.
    outcome              VARCHAR(64)   NOT NULL,
    agent_user_uuid      UUID,
    agent_subject        VARCHAR(254)  NOT NULL,
    query_kind           VARCHAR(32),
    query_masked         VARCHAR(128),
    customer_keys        TEXT,
    -- Comma-separated section names the response carried (console,innbucksApp).
    sections             VARCHAR(255),
    -- Per section, the ids a later call may target (JSON). Binding reads it.
    section_targets      TEXT,
    target               VARCHAR(128),
    staff_account        BOOLEAN       NOT NULL DEFAULT FALSE,
    client_ip_untrusted  VARCHAR(64)
);

-- A lookup id is issued once: the binding reads "the SEARCH row with this id".
CREATE UNIQUE INDEX uk_support_access_log_lookup
    ON support_access_log (lookup_id) WHERE op = 'SEARCH';
-- "Everything this agent looked at" (reviews, insider-threat questions).
CREATE INDEX idx_support_access_log_agent_time
    ON support_access_log (agent_user_uuid, created_at DESC);
-- Retention sweep.
CREATE INDEX idx_support_access_log_time ON support_access_log (created_at);

-- One row per support write, keyed by (agent, Idempotency-Key): a repeat of the
-- same key returns the stored outcome instead of acting twice. The UNIQUE index
-- is also what serialises two concurrent requests carrying the same key — the
-- second insert waits on the first transaction and then fails, and the caller
-- is answered from the first one's row.
--
-- target is the id acted on (users.id for the console section); customer_key is
-- the masked customer key the seal names. case_id is NULL until support cases
-- exist (PR 6), which will make it required when the agent has an open case.
CREATE TABLE support_actions (
    id                   BIGSERIAL     PRIMARY KEY,
    agent_user_uuid      UUID          NOT NULL,
    idempotency_key      UUID          NOT NULL,
    lookup_id            VARCHAR(16)   NOT NULL,
    section              VARCHAR(32)   NOT NULL,
    op                   VARCHAR(64)   NOT NULL,
    target               VARCHAR(128)  NOT NULL,
    customer_key         VARCHAR(128),
    -- PENDING while the action runs inside its transaction (never committed as
    -- PENDING for an in-process write), then SUCCESS / FAILURE / UNKNOWN.
    outcome              VARCHAR(16)   NOT NULL,
    what_happens_next    VARCHAR(1000),
    case_id              VARCHAR(64),
    created_at           TIMESTAMP     NOT NULL,
    completed_at         TIMESTAMP,
    CONSTRAINT uk_support_actions_agent_key UNIQUE (agent_user_uuid, idempotency_key)
);

CREATE INDEX idx_support_actions_lookup ON support_actions (lookup_id);
