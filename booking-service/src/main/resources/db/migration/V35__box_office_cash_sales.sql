-- Box office: cash sales at the organizer's outlet (POST /bookings/box-office).
--
-- A cash sale is an ordinary booking, created and CONFIRMED in one request by
-- the event's organizer, with payment_rail = 'CASH'. These columns say who
-- sold it, and who voided it if it was voided, so the organizer can reconcile
-- the till (GET /event-organizer/reports/cash-up).
--
-- sold_by_user_uuid / sold_by_name: the organizer account that took the cash.
--   NULL on every online booking: that, not the rail, is what marks a box
--   office sale.
-- voided_* / void_reason: a voided cash sale (cancel_reason CASH_VOIDED):
--   who, when and why. A void is refused once a ticket is scanned or the
--   event has started.
--
-- cancel_reason gains CASH_VOIDED (never resurrected: the buyer took the cash
-- back). The CHECK is dropped and re-added NOT VALID, then validated, so the
-- table is not locked for the scan.
--
-- Additive: nullable columns with no default are catalog-only changes.
SET LOCAL lock_timeout = '10s';

ALTER TABLE bookings ADD COLUMN sold_by_user_uuid   UUID;
ALTER TABLE bookings ADD COLUMN sold_by_name        VARCHAR(254);
ALTER TABLE bookings ADD COLUMN voided_by_user_uuid UUID;
ALTER TABLE bookings ADD COLUMN voided_by_name      VARCHAR(254);
ALTER TABLE bookings ADD COLUMN voided_at           TIMESTAMP;
ALTER TABLE bookings ADD COLUMN void_reason         VARCHAR(500);

ALTER TABLE bookings DROP CONSTRAINT chk_bookings_cancel_reason;
ALTER TABLE bookings ADD CONSTRAINT chk_bookings_cancel_reason
    CHECK (cancel_reason IN ('EXPIRED', 'CUSTOMER_CANCELLED', 'BUYER_RELEASED', 'ADMIN_REVERSED',
                             'CASH_VOIDED')) NOT VALID;
ALTER TABLE bookings VALIDATE CONSTRAINT chk_bookings_cancel_reason;
