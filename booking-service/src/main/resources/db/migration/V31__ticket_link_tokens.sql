-- Public ticket pages get a key of their own.
--
-- The buyer's page (every ticket's QR), the checkout's status poll and a
-- holder's one-ticket page were opened by the booking's or the ticket's row
-- id. Those ids are on staff screens, scan reports, support tools and logs,
-- so anyone who could read one could open the QR codes it led to. Each
-- booking and each ticket now carries a random link token (128 bits from the
-- app, 122 from gen_random_uuid() for the rows below); every message sent
-- from this release on links by token, and the id routes close behind the
-- app.booking.id-links switch (IdLinkPolicy).
--
-- The DEFAULT is the backfill, and it stays: during the rolling update the
-- previous image still inserts bookings and items without the column, and a
-- volatile default gives each of those rows a token of its own. The app sets
-- the value itself (LinkTokens), so the default only ever fills rows it did
-- not write.
--
-- Adding a column with a volatile default rewrites both tables under an
-- ACCESS EXCLUSIVE lock: bookings cannot be read or written for the seconds
-- that takes. lock_timeout makes the migration fail (and the new pod not
-- start, the old one serving on) rather than queue every booking request
-- behind a long-running transaction.
SET LOCAL lock_timeout = '10s';

ALTER TABLE bookings
    ADD COLUMN link_token VARCHAR(32) NOT NULL DEFAULT replace(gen_random_uuid()::text, '-', '');

ALTER TABLE booking_items
    ADD COLUMN link_token VARCHAR(32) NOT NULL DEFAULT replace(gen_random_uuid()::text, '-', '');

CREATE UNIQUE INDEX uq_bookings_link_token ON bookings (link_token);
CREATE UNIQUE INDEX uq_booking_items_link_token ON booking_items (link_token);
