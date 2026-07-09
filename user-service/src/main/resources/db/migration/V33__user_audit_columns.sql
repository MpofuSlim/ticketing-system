-- Per-row audit trail (created/updated when + by whom) on the admin/organizer-
-- managed entities, auto-populated by JPA auditing (@CreatedBy/@LastModifiedBy +
-- JpaAuditingConfig) plus a mapped updated_at (@PreUpdate, UTC).
--
-- These complement the tamper-evident audit_events log: audit_events records
-- specific security actions; these columns answer "who last touched THIS row".
-- created_at already exists on all three tables; add updated_at + the actor
-- columns. TIMESTAMP (no tz) matches the entities' LocalDateTime mapping under
-- ddl-auto=validate. Actor columns hold a user_uuid string or JWT email (VARCHAR).
-- All nullable — no historical actor to backfill; self-registration / login-time
-- writes legitimately have no authenticated principal.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE service_requests
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE team_member_event_assignment
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);
