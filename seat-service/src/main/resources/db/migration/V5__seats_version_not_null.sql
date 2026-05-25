-- Optimistic-lock correctness: seats.version must never be NULL.
--
-- The column was created nullable (BIGINT, no default). Hibernate's @Version
-- handling treats a NULL version specially and a legacy row with version=NULL
-- can slip an optimistic-lock check. Backfill existing NULLs to 0, then pin
-- NOT NULL DEFAULT 0 so every row — old and new — carries a real version.

UPDATE seats SET version = 0 WHERE version IS NULL;

ALTER TABLE seats ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE seats ALTER COLUMN version SET NOT NULL;
