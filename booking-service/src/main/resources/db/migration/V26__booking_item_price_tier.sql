-- Price tiers (seat-service V9): which tier each ticket was sold at.
--
-- price_tier_id is the seat-service tier's id, NULL for a ticket sold at the
-- category's full price (and for every pre-V26 row). price_tier_name is a
-- snapshot of the tier's name when the ticket was sold, so the receipt and the
-- organizer's report still name it after the tier is renamed or deleted.
-- No foreign key: the tier lives in seat-service's database.
--
-- A tier with a quantity ("the first 100 tickets") is counted from these rows
-- by BookingService.persistBooking, under the category's category_inventory
-- row lock: every booking of the category takes that lock first, so the count
-- and the insert that follows cannot interleave with another booking's. The
-- partial index serves that count (and the per-tier counts seat-service shows)
-- without touching the far more numerous full-price rows.
--
-- Plain CREATE INDEX for the reason V24 gives: Flyway runs this in a
-- transaction and the write lock is brief at today's row counts.
ALTER TABLE booking_items
    ADD COLUMN price_tier_id   UUID,
    ADD COLUMN price_tier_name VARCHAR(60);

CREATE INDEX IF NOT EXISTS idx_booking_items_category_price_tier
    ON booking_items (category_id, price_tier_id)
    WHERE price_tier_id IS NOT NULL;
