-- Day-before ORGANIZER reminder marker: one row per event whose organizer has
-- been sent (or silently skipped for started events) the pre-event headline
-- email — confirmed bookings + tickets sold, "is your scanning team ready".
-- Attendee reminders track per-booking on the bookings table (V18/V19); the
-- organizer reminder is per-event, hence its own tiny marker table.
CREATE TABLE organizer_event_reminders (
    event_id UUID PRIMARY KEY,
    sent_at  TIMESTAMP NOT NULL
);
