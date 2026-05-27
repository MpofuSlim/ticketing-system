-- Replace province with country (stamped from the organizer's JWT), add an
-- event category, and split the single event timestamp into start + end.
--
-- Backfill rationale for existing rows:
--   * country  -> 'Zimbabwe'. Every legacy row carried a Zimbabwean province
--                 code (HRE/BYO/MID/...), so the country is unambiguous.
--   * category -> 'CONCERT'. The most common ticketed event; a safe default.
--   * end_date_time -> start + 2h, so the NOT NULL and end-after-start
--                 invariants hold for rows created before the split.

-- --- country ---------------------------------------------------------------
ALTER TABLE events ADD COLUMN country VARCHAR(255);
UPDATE events SET country = 'Zimbabwe' WHERE country IS NULL;
ALTER TABLE events ALTER COLUMN country SET NOT NULL;

-- --- category --------------------------------------------------------------
ALTER TABLE events ADD COLUMN category VARCHAR(20);
UPDATE events SET category = 'CONCERT' WHERE category IS NULL;
ALTER TABLE events ALTER COLUMN category SET NOT NULL;
ALTER TABLE events
    ADD CONSTRAINT chk_events_category
        CHECK (category IN ('BOOKS', 'COMEDY', 'HALF_MARATHON', 'MARATHON', 'CONCERT', 'SPORT'));

-- --- start / end timestamps ------------------------------------------------
-- Renaming carries the uk_events_natural_key constraint and idx_events_date_time
-- index onto the new column automatically (Postgres rewrites their references).
ALTER TABLE events RENAME COLUMN date_time TO start_date_time;
ALTER TABLE events ADD COLUMN end_date_time TIMESTAMP;
UPDATE events SET end_date_time = start_date_time + INTERVAL '2 hours' WHERE end_date_time IS NULL;
ALTER TABLE events ALTER COLUMN end_date_time SET NOT NULL;

-- --- drop province artefacts ----------------------------------------------
DROP INDEX IF EXISTS idx_events_province_active;
ALTER TABLE events DROP CONSTRAINT IF EXISTS chk_events_province;
ALTER TABLE events DROP COLUMN province;

-- --- lookup index for /events/by-country -----------------------------------
CREATE INDEX IF NOT EXISTS idx_events_country_active
    ON events (country) WHERE deleted = FALSE AND active = TRUE;
