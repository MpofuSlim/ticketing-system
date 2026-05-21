-- V5: Two pre-staging concurrency invariants for booking-service.
--
-- 1. bookings.version — optimistic-lock token. Hibernate's @Version makes
--    confirmBooking fail the second of two concurrent confirms (the older
--    behaviour silently let both run applyLoyalty -> double redeem + double
--    earn). The application surfaces OptimisticLockException as 409 Conflict.
--
-- 2. booking_items.is_active + partial unique index — closes the seat-pick
--    race in createBooking where two bookers' "is this seat free?"
--    cross-checks could both see the seat available (one row not yet
--    visible to the other's transaction), then both insert booking_items
--    rows pointing at the same seat_id.
--
--    The constraint "at most one active booking_item per seat" reads from
--    BOTH tables (booking_items.seat_id, bookings.status), which a single
--    unique index can't express directly. We denormalise booking.status
--    onto booking_items.is_active via an AFTER UPDATE trigger on
--    bookings.status, then put a partial unique index on the boolean.
--    Trigger keeps the two in sync without burdening application code.

ALTER TABLE bookings
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE booking_items
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

-- Backfill: existing CANCELLED bookings get their items marked inactive
-- so the new partial unique index doesn't trip on legacy rows.
UPDATE booking_items bi
   SET is_active = FALSE
  FROM bookings b
 WHERE bi.booking_id = b.id
   AND b.status = 'CANCELLED';

-- Trigger function: sync booking_items.is_active to follow booking.status.
-- The function is intentionally idempotent — re-running an UPDATE with the
-- same target status is safe.
CREATE OR REPLACE FUNCTION sync_booking_items_is_active() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status = 'CANCELLED' AND (OLD.status IS NULL OR OLD.status <> 'CANCELLED') THEN
        UPDATE booking_items SET is_active = FALSE WHERE booking_id = NEW.id;
    ELSIF NEW.status IN ('PENDING', 'CONFIRMED')
          AND OLD.status = 'CANCELLED' THEN
        -- Booking resurrected (rare — refund/un-cancel flow). Re-activate
        -- the items but only if no other active row already claims any of
        -- the same seats; otherwise the partial unique index will reject
        -- the UPDATE and the whole transaction will roll back, which is
        -- the correct outcome (the seat was re-sold).
        UPDATE booking_items SET is_active = TRUE WHERE booking_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_bookings_sync_items_active ON bookings;

CREATE TRIGGER trg_bookings_sync_items_active
    AFTER UPDATE OF status ON bookings
    FOR EACH ROW
    EXECUTE FUNCTION sync_booking_items_is_active();

-- The actual race-blocking constraint. Partial — cancelled rows are excluded
-- so a seat can be re-booked after a PENDING expiry. Two simultaneous
-- createBooking calls aiming at the same seat both try to insert with
-- is_active=true; Postgres serialises the inserts on the index and the
-- loser gets a SQLException that booking-service turns into a 409.
CREATE UNIQUE INDEX uq_active_booking_item_per_seat
    ON booking_items (seat_id)
    WHERE is_active;
