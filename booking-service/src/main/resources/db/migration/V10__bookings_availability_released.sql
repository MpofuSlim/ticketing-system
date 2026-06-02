-- Per-booking idempotency flag for the event-service availability release.
--
-- Reversing a CONFIRMED booking (admin refund, future real-payment failure
-- compensation) restores its consumed seats by calling event-service's
-- PATCH /events/{id}/availability/release. That call is best-effort over
-- HTTP; if it succeeds but the local commit fails (network blip), the next
-- retry would double-credit without a guard.
--
-- availability_released = true after a successful release means the reverse
-- handler can safely no-op the release call on retry. Defaults to false on
-- every existing row — none of them have been released yet, by definition.
ALTER TABLE bookings
    ADD COLUMN availability_released BOOLEAN NOT NULL DEFAULT FALSE;
