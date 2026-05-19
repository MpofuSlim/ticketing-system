-- Make the DB the source of truth for seat-lock expiry.
--
-- Previously the Redis TTL was the only thing tracking when a LOCKED seat
-- should become AVAILABLE again. If Redis expired (or was restarted) the
-- seat.status stayed LOCKED forever with no live owner, permanently removing
-- the seat from inventory. lock_expires_at lets SeatLockReaper sweep stale
-- locks without consulting Redis.

ALTER TABLE seats
    ADD COLUMN lock_expires_at TIMESTAMP NULL;

-- Partial index: the reaper only queries LOCKED rows, so we don't waste
-- index pages on AVAILABLE/BOOKED rows (which are the vast majority).
CREATE INDEX IF NOT EXISTS idx_seats_lock_expires_at
    ON seats (lock_expires_at)
    WHERE status = 'LOCKED';

-- Backfill: any seat already LOCKED at migration time has no expiry recorded,
-- so the reaper would never touch it. Stamp them as expired right now so the
-- next reaper sweep returns them to inventory. Users mid-flow on those locks
-- will see "Lock expired" on confirm and retry — acceptable one-time cost to
-- unblock previously orphaned seats.
UPDATE seats
SET lock_expires_at = NOW()
WHERE status = 'LOCKED' AND lock_expires_at IS NULL;
