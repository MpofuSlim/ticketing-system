-- OWASP A09 — hash-chaining for the append-only audit log (deletion/reorder
-- detection). Completes the "deliberately separate, heavier step" flagged in
-- V29: per-row row_hmac proves a row's CONTENT was not altered, but says
-- nothing about whether whole rows were DELETED, REORDERED, or the tail
-- TRUNCATED — an attacker with DB write access can drop the row recording their
-- own break-in and every surviving row still verifies its own row_hmac.
--
-- Fix: bind each row to its predecessor with a keyed chain tag
--   chain_hmac = HMAC-SHA256(audit.hmac-secret, prev_chain_hmac || 0x1F || row_hmac)
-- computed by AuditService on the write path. Deleting/reordering any row breaks
-- the link at the next surviving row (the recomputed chain_hmac no longer matches
-- the stored one), and — crucially — the attacker cannot repair the downstream
-- chain because forging a new chain_hmac needs the HMAC key, which lives in
-- app config/env (guarded by ProductionSecretsGuard), NOT in the database.
-- AuditIntegrityVerifier walks the chain and emits security.audit.chain.broken.
--
-- Write-path serialisation: concurrent audit writes must append to ONE chain, or
-- two rows could both build on the same predecessor and fork it. We serialise on
-- a single-row bookkeeping table (audit_chain_head) taken with SELECT ... FOR
-- UPDATE inside the same REQUIRES_NEW audit transaction — DB-agnostic (works on
-- Postgres and the H2 test profile), no advisory-lock or sequence tricks. The
-- head row holds the last appended chain_hmac (the next write's predecessor) and
-- the last event id for forensic reference. Seeded here with a NULL chain_hmac,
-- so the first chained row is the genesis anchor.
--
-- Backward compatible: rows written before this migration carry chain_hmac =
-- NULL and are reported as "legacy" (chain not applicable), never as "broken",
-- exactly as row_hmac did for pre-V29 rows. VARCHAR(64) holds the hex-encoded
-- SHA-256 (32 bytes -> 64 hex chars), matching row_hmac and every other
-- audit_events column so ddl-auto: validate is satisfied.

ALTER TABLE audit_events ADD COLUMN chain_hmac VARCHAR(64);

CREATE TABLE audit_chain_head (
    id            INTEGER     PRIMARY KEY,
    chain_hmac    VARCHAR(64),
    last_event_id BIGINT
);

-- The single serialisation row. id is always 1; the app locks it FOR UPDATE on
-- every audit write. NULL chain_hmac => the next write is the genesis link.
INSERT INTO audit_chain_head (id, chain_hmac, last_event_id) VALUES (1, NULL, NULL);
