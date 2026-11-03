-- The event review step (owner decision, 2026-11-03).
--
-- An event's review status is DERIVED, never stored as its own column
-- (ReviewStatus.of), so it can never disagree with the flags it is read from:
--   rejected = true            -> REJECTED   (the existing moderation flag)
--   approved_at IS NOT NULL    -> APPROVED   (allowed to go live)
--   submitted_at IS NOT NULL   -> SUBMITTED  (awaiting a staff decision)
--   otherwise                  -> DRAFT
--
-- approved_by is the staff member who approved, or NULL for an event that went
-- live without a review (published while events.review.required was off, or
-- live before this migration). rejection_reason is what staff told the
-- organizer; it stays until the event is resubmitted or approved.
--
-- Every event live today is APPROVED by this backfill, so turning the review
-- step on never takes one down. An unpublished draft stays DRAFT and goes
-- through review when it is next published with the step on. Visibility is
-- still `active` and `rejected`: booking-service's sales window reads only
-- those, so nothing about selling changes here.
--
-- Four nullable columns with no default are catalog-only changes in Postgres
-- (no table rewrite); the backfill touches only live events. lock_timeout bounds
-- the brief ACCESS EXCLUSIVE lock so a long reader fails the migration fast
-- rather than queueing every event request behind it.
SET LOCAL lock_timeout = '10s';

ALTER TABLE events ADD COLUMN submitted_at TIMESTAMP;
ALTER TABLE events ADD COLUMN approved_at TIMESTAMP;
ALTER TABLE events ADD COLUMN approved_by VARCHAR(254);
ALTER TABLE events ADD COLUMN rejection_reason VARCHAR(1000);

UPDATE events
   SET approved_at = COALESCE(updated_at, created_at, NOW() AT TIME ZONE 'UTC')
 WHERE active = TRUE
   AND rejected = FALSE
   AND deleted = FALSE;

-- The staff queue: events awaiting a decision, oldest submission first.
CREATE INDEX idx_events_awaiting_review
    ON events (submitted_at)
 WHERE submitted_at IS NOT NULL AND approved_at IS NULL AND rejected = FALSE AND deleted = FALSE;
