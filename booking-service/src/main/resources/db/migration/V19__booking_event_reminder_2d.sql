-- Second pre-event reminder stage: T-2-days, over SMS + email (the existing
-- reminder_sent_at / V18 stage is the day-of nudge). Same exactly-once
-- discipline: reminder2d_sent_at records the attempt (success or failure, and
-- stamped silently when the day-of window has already been reached so a
-- late-booked customer gets ONE reminder, not two back-to-back).
ALTER TABLE bookings
    ADD COLUMN reminder2d_sent_at TIMESTAMP;

-- Mirror of idx_bookings_reminder_pending (V18) for the new stage's scan.
CREATE INDEX idx_bookings_reminder2d_pending
    ON bookings (event_id)
    WHERE status = 'CONFIRMED' AND reminder2d_sent_at IS NULL;
