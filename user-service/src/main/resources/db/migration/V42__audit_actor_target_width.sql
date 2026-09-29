-- Widen audit_events.actor_id / target_id from VARCHAR(64) to VARCHAR(254).
--
-- actor_id holds the acting administrator's EMAIL on every admin action
-- (authentication.getName()), and an email may be up to 254 characters
-- (RFC 5321). At 64, an administrator with a longer address could not be
-- audited at all: the INSERT failed, AuditService swallows write failures by
-- design (a broken audit path must not break login), and the row was silently
-- lost — the one thing an audit log must never do quietly. target_id is widened
-- with it because some rows (an unknown-identifier login failure) record the
-- offered identifier there.
--
-- Safe for the tamper-evidence chain: widening a VARCHAR does not rewrite a
-- single stored value, so every row_hmac (V29) and chain_hmac (V32) still
-- verifies. In Postgres, raising a varchar length limit is a catalog-only
-- change — no table rewrite, no index rebuild — but ALTER TABLE still takes a
-- brief ACCESS EXCLUSIVE lock. audit_events is written on every login, so fail
-- the migration fast rather than queue behind (and block) live audit writers;
-- a failed attempt is simply retried on the next rollout.
--
-- No new index: idx_audit_events_target_id (target_id, occurred_at DESC) has
-- existed since V15 and survives the type change.
SET LOCAL lock_timeout = '5s';

ALTER TABLE audit_events ALTER COLUMN actor_id  TYPE VARCHAR(254);
ALTER TABLE audit_events ALTER COLUMN target_id TYPE VARCHAR(254);
