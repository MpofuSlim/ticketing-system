-- Payments generalization: the InnBucks 2D-code rail now serves MORE than
-- ticket bookings (first new product: marketplace orders), so the ledger's
-- order identity generalizes from booking_id (UUID, booking-only) to
-- (order_type, order_ref):
--
--   order_type  which product the money collects for ('BOOKING' | 'MARKETPLACE')
--   order_ref   the product-side reference: the booking UUID in canonical
--               text form for BOOKING rows, the opaque MKT-... order ref for
--               MARKETPLACE rows
--
-- booking_id stays populated for BOOKING rows (legacy response echo /
-- back-compat + the existing idx_payment_booking lookups) but is no longer
-- the ledger's identity — MARKETPLACE rows carry NULL there.

ALTER TABLE payment ADD COLUMN order_type VARCHAR(16) NOT NULL DEFAULT 'BOOKING';
ALTER TABLE payment ADD COLUMN order_ref  VARCHAR(64);

-- Backfill: every pre-generalization row is a booking payment; uuid::text
-- renders the canonical lowercase form, which is exactly what the
-- application writes for new BOOKING rows (UUID.toString()).
UPDATE payment SET order_ref = booking_id::text WHERE order_ref IS NULL;

ALTER TABLE payment ALTER COLUMN order_ref SET NOT NULL;

-- Same vocabulary discipline as chk_payment_status: an unknown product name
-- can only enter the ledger through a deliberate migration.
ALTER TABLE payment ADD CONSTRAINT chk_payment_order_type
    CHECK (order_type IN ('BOOKING', 'MARKETPLACE'));

-- Legacy column: still written for BOOKING rows, NULL for everything else.
ALTER TABLE payment ALTER COLUMN booking_id DROP NOT NULL;

-- INVARIANT (generalized from V5's uq_payment_active_booking): at most ONE
-- active-or-successful payment per (order_type, order_ref), enforced by the
-- DATABASE — application-level checks lie under concurrency; this index is
-- the arbiter. Terminal failures (FAILED / REJECTED / EXPIRED) free the slot
-- so a customer can retry after a decline/lapse; anything else blocks a
-- second row. PaymentRecordService.ACTIVE_OR_SUCCEEDED must stay the exact
-- complement of this WHERE clause.
DROP INDEX uq_payment_active_booking;
CREATE UNIQUE INDEX uq_payment_active_order
    ON payment(order_type, order_ref)
    WHERE status NOT IN ('FAILED', 'REJECTED', 'EXPIRED');

-- "Was this order ever paid?" — the replay/pre-check lookups now key on
-- order_ref. idx_payment_booking stays for legacy booking_id lookups.
CREATE INDEX idx_payment_order_ref
    ON payment(order_ref);
