-- Find a phone's OTP challenges by phone. cutOff() voids a phone's OPEN challenges
-- whenever it is blocked, banned, removed or — with one phone per number — signed
-- out by another phone of its number, which runs while the broker's login result
-- holds the number's lock; DeviceOtpService.open() looks for a reusable one; and
-- the ON DELETE CASCADE from customer_devices (the retention sweep) deletes them.
-- Each of those scanned the whole table. Same shape as idx_device_tickets_device.
--
-- Additive: an older image ignores it, so rolling the image back is safe. The table
-- keeps 7 days of challenges, so the build takes milliseconds; the lock_timeout
-- makes it fail fast rather than queue behind a sign-in holding the table.
SET LOCAL lock_timeout = '5s';

CREATE INDEX idx_device_otp_device ON device_otp_challenges (customer_device_id);
