-- V25: the payment window, and why a booking was cancelled (H2).
--
-- payment_open_until — the latest instant a payment for this booking may still
-- land. payment-service extends the hold (PATCH /bookings/internal/{id}/
-- extend-hold) before it mints EVERY instrument (InnBucks code, EcoCash
-- prompt, card checkout), and extend-hold stamps the deadline it was given
-- here, never moving it earlier. So "payment_open_until > now" reads "a payment
-- may be live": the customer cancel refuses while it holds (409
-- PAYMENT_IN_PROGRESS), and the cancellation notice of an expired hold is
-- skipped when it is NULL (the buyer never started paying). NULL on every row
-- that never reached a payment, and on every pre-V25 row.
--
-- cancel_reason — why a CANCELLED booking was cancelled: the expiry sweep
-- (EXPIRED), the buyer's own cancel (CUSTOMER_CANCELLED), a public release
-- (BUYER_RELEASED, written from P2-4) or a SUPER_ADMIN reversal of a paid
-- booking (ADMIN_REVERSED). A payment that confirms after the booking was
-- cancelled resurrects it for the first three, never for ADMIN_REVERSED, and
-- never for a NULL — every row cancelled before V25, whose reason nobody
-- recorded. A resurrected booking is CONFIRMED again and its reason cleared.
--
-- Both columns are nullable with no default, so ADD COLUMN is catalog-only (no
-- table rewrite). The CHECK is added NOT VALID and then validated, the shape
-- that would let the validation scan run under a weaker lock on its own; here
-- the ADD COLUMN already holds ACCESS EXCLUSIVE, and the scan finds only NULLs.
-- bookings is written on every booking, confirm and cancel, so fail fast
-- rather than queue behind (and block) live writers; a failed attempt is
-- retried on the next rollout.
SET LOCAL lock_timeout = '5s';

ALTER TABLE bookings
    ADD COLUMN payment_open_until TIMESTAMP,
    ADD COLUMN cancel_reason      VARCHAR(20);

ALTER TABLE bookings ADD CONSTRAINT chk_bookings_cancel_reason
    CHECK (cancel_reason IN ('EXPIRED', 'CUSTOMER_CANCELLED', 'BUYER_RELEASED', 'ADMIN_REVERSED')) NOT VALID;

ALTER TABLE bookings VALIDATE CONSTRAINT chk_bookings_cancel_reason;
