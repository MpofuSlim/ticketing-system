-- Optimistic-lock correctness: events.version must never be NULL.
-- Same rationale as seat-service's seats.version fix — the column was
-- created nullable; a NULL version weakens the @Version check. Backfill
-- to 0, then pin NOT NULL DEFAULT 0.

UPDATE events SET version = 0 WHERE version IS NULL;

ALTER TABLE events ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE events ALTER COLUMN version SET NOT NULL;
