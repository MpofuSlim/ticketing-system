-- Who is actually coming.
--
-- Until now a booking identified its buyer only by phone (+ optional email),
-- so an organizer's "who bought tickets" view was a list of MSISDNs. Two
-- additions:
--
-- 1. bookings.customer_name — the PURCHASER's full name, captured at
--    POST /bookings. Nullable only for rows that pre-date this migration;
--    the create path requires it from here on (body field, or the JWT's
--    firstName/lastName for an authenticated customer).
--
-- 2. booking_items.attendee_* — an OPTIONAL per-ticket attendee. A buyer
--    taking friends can name who holds each ticket and give that person's
--    contact, in which case THAT ticket's QR/email is also delivered to them
--    directly (TicketDeliveryService). Null = the ticket is the purchaser's
--    own. Per-ticket rather than per-booking because a 3-ticket booking has
--    three holders, each needing their own QR on their own phone.
--
-- attendee_phone is stored canonicalised to E.164 (the controller validates
-- via MsisdnValidator exactly as it does the purchaser's number), so the
-- delivery path can hand it straight to the WhatsApp gateway.
--
-- No index: nothing queries by attendee; the organizer views read attendees
-- through the booking -> items join they already perform.

ALTER TABLE bookings
    ADD COLUMN customer_name VARCHAR(255);

ALTER TABLE booking_items
    ADD COLUMN attendee_name  VARCHAR(255),
    ADD COLUMN attendee_email VARCHAR(255),
    ADD COLUMN attendee_phone VARCHAR(32);
