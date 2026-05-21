-- V6: Index on bookings.phone_number.
--
-- Booking.java line 27-30 documents this column as "Indexed so
-- `findByPhoneNumber*` lookups don't full-scan" — but V2's index list
-- never actually created it. GET /bookings/phone/{phoneNumber} is
-- permitAll and reachable anonymously through the gateway, so today's
-- behaviour is an anonymous-DoS-by-scan: probe the endpoint with
-- random phone numbers and every miss does a sequential scan of the
-- entire bookings table.

CREATE INDEX IF NOT EXISTS idx_bookings_phone_number
    ON bookings (phone_number);
