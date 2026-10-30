-- Which payment rail paid for a booking (V29): INNBUCKS_CODE, ECOCASH or
-- ZIMSWITCH_CARD, as payment-service names them. payment-service sends it with
-- the confirm (PATCH /bookings/internal/{id}/confirm, body "paymentRail"), and
-- the organizer's guest list and CSV export show it as the payment method.
--
-- No CHECK on purpose: a rail payment-service adds later must never make a
-- paid confirm fail here. BookingService keeps only a plain upper-case name.
-- NULL = not recorded: every booking confirmed before this, and any confirm
-- that did not say. Bookings already confirmed are filled in once by the
-- operator script in the PR (payment-service's database holds the rail).

ALTER TABLE bookings ADD COLUMN payment_rail VARCHAR(32);
