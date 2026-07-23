-- Adds FUN_RUN to the allowed event categories. chk_events_category was
-- created in V4 with a fixed value list, so extending the EventCategory
-- enum requires recreating the constraint with the new set. Existing rows
-- all hold values from the old list, so the new (superset) check passes
-- without a data migration.
ALTER TABLE events DROP CONSTRAINT chk_events_category;
ALTER TABLE events
    ADD CONSTRAINT chk_events_category
        CHECK (category IN ('BOOKS', 'COMEDY', 'FUN_RUN', 'HALF_MARATHON', 'MARATHON', 'CONCERT', 'SPORT'));
