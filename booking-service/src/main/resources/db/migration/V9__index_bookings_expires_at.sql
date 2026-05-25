-- The PENDING-booking expiry sweep (BookingExpirationService) queries
-- bookings WHERE status = 'PENDING' AND expires_at < now(). expires_at was
-- unindexed, so the sweep was a full table scan that grows with total
-- booking history forever.
--
-- Partial index (WHERE status = 'PENDING') keeps it sized to the live
-- PENDING population only — a row leaves the index the moment it's
-- confirmed or cancelled. Same pattern as the loyalty pending-user and
-- event-outbox partial indexes.

CREATE INDEX IF NOT EXISTS idx_bookings_pending_expires_at
    ON bookings (expires_at)
    WHERE status = 'PENDING';
