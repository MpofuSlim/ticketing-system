-- Add-ons bought with a ticket (seat-service V12): e.g. a "Parking pass" on a
-- VIP ticket, at most one of each per ticket.
--
-- booking_items.add_ons: the ticket's add-ons as bought, a JSON array of
-- {addOnId, name, price} (NULL when none). The name and price are kept here, so
-- an organizer editing or removing an add-on never restates a sale. Not
-- sensitive: shown on the ticket, the guest list and the gate scan.
--
-- bookings.add_ons_amount: the add-ons' total, already inside total_amount
-- (which is what is paid). NULL when the booking has none. price_at_booking
-- stays the ticket's own price, so the per-category and tier reports count
-- tickets alone, and every report on total_amount includes add-ons.
--
-- An add-on's limit is counted from these rows (bookings not CANCELLED) under
-- the category's stock row lock, which makes it exact.
--
-- Additive: nullable columns with no default are catalog-only changes.
SET LOCAL lock_timeout = '10s';

ALTER TABLE booking_items ADD COLUMN add_ons TEXT;
ALTER TABLE bookings ADD COLUMN add_ons_amount NUMERIC(10, 2);
