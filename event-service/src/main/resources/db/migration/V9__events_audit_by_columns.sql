-- Audit "by whom": the acting principal is stamped on create/update via Spring
-- Data JPA auditing (@CreatedBy / @LastModifiedBy in Event, wired by
-- JpaAuditingConfig). created_at / updated_at already exist (V1); this adds only
-- the actor columns. Nullable — legacy rows and any system/unauthenticated write
-- leave them null. Holds the caller's user_uuid (or JWT-subject email fallback),
-- so VARCHAR, not UUID.
ALTER TABLE events ADD COLUMN IF NOT EXISTS created_by VARCHAR(255);
ALTER TABLE events ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);
