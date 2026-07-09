-- Audit trail on the admin-created seating entities. created_by / updated_by are
-- auto-stamped by JPA auditing (@CreatedBy/@LastModifiedBy + JpaAuditingConfig);
-- the Seat entity also gains a mapped updated_at (@PreUpdate, UTC).
--
-- seats already had created_at (V1) but no updated_at; seat_categories already
-- had both. TIMESTAMP (no tz) matches the entities' LocalDateTime mapping under
-- ddl-auto=validate. Actor columns hold an organizer uuid string or JWT email,
-- hence VARCHAR. All nullable — no historical actor to backfill.

ALTER TABLE seats
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE seat_categories
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);
