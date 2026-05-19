-- Refresh-token binding: tie each refresh-token row to the device that
-- minted it via a SHA-256 hash of the FE-supplied device id.
--
-- Attack closed: a refresh token leaked off device A (storage scrape,
-- debug-mode dump, MITM with malicious CA, etc.) can't be replayed
-- from device B. On rotate, the service compares the hash of the
-- X-Device-Id header against the stored hash; mismatch fires the same
-- ReuseDetectedException as a replayed token (family revoked, attacker
-- AND legitimate client both forced to re-authenticate).
--
-- Nullable for backward compat: rows minted BEFORE this rollout don't
-- carry a hash. Refresh-token rotation treats a NULL stored hash as
-- "legacy session, no device enforcement" — old clients keep working
-- through their refresh-token's 7-day TTL, by which point every
-- session is post-rollout and bound. New rows minted after the FE
-- starts sending X-Device-Id store the hash and ARE enforced.

ALTER TABLE refresh_tokens
    ADD COLUMN device_id_hash VARCHAR(64);

-- Supports a future GET /auth/devices listing (operator + customer
-- self-service device management). Partial — only rows that carry a
-- hash get indexed, so the index doesn't bloat with the legacy NULL
-- rows that linger during the rollout window.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_device_id_hash
    ON refresh_tokens(device_id_hash)
    WHERE device_id_hash IS NOT NULL;
