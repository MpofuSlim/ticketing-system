-- Pre-event reminder tracking: the hourly EventReminderScheduler sends each
-- CONFIRMED booking one WhatsApp reminder when its event starts within the
-- reminder window. reminder_sent_at records the attempt (success or failure,
-- and back-dated silently for events already started) so a booking is never
-- reminded twice and old rows drop out of the scan.
ALTER TABLE bookings
    ADD COLUMN reminder_sent_at TIMESTAMP;

-- The scheduler's scan: distinct events having CONFIRMED, not-yet-reminded
-- bookings. Partial index keeps it cheap as the bookings table grows.
CREATE INDEX idx_bookings_reminder_pending
    ON bookings (event_id)
    WHERE status = 'CONFIRMED' AND reminder_sent_at IS NULL;
