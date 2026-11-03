-- What a buyer accepted and answered at checkout (event-service V16).
--
-- bookings.terms_version / terms_accepted_at: the version of the event's terms
-- and conditions the buyer accepted, and when. NULL when the event had no
-- terms. Kept for the life of the booking: it is the consent record.
--
-- booking_items.answers: that ticket's answers to the organizer's booking
-- questions, as AES-GCM ciphertext ("v1:" + base64; key BOOKING_ANSWERS_KEY,
-- BookingAnswersCipher). The plaintext is a JSON array of
-- {questionId, label, type, value}: the label and type are copied in so a
-- question renamed or removed later never changes what was answered.
--
-- bookings.answers_purge_after: the event's end + 90 days, when the answers
-- are deleted (BookingAnswersRetentionJob re-checks the event's live end
-- first, so a postponed event keeps them). NULL when nothing is to delete.
-- answers_purged_at: when they were deleted, so a report can say so.
--
-- Additive: nullable columns with no default are catalog-only changes. The
-- lock_timeout makes a busy table fail this migration fast (the old pod
-- serves on) rather than queue every booking request behind it.
SET LOCAL lock_timeout = '10s';

ALTER TABLE bookings
    ADD COLUMN terms_version INTEGER,
    ADD COLUMN terms_accepted_at TIMESTAMP,
    ADD COLUMN answers_purge_after TIMESTAMP,
    ADD COLUMN answers_purged_at TIMESTAMP;

ALTER TABLE booking_items
    ADD COLUMN answers TEXT;

-- The retention job's only read: bookings whose answers are due.
CREATE INDEX idx_bookings_answers_purge_after ON bookings (answers_purge_after)
    WHERE answers_purge_after IS NOT NULL;
