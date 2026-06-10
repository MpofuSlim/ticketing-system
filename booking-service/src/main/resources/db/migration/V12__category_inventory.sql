-- V12: per-category ticket inventory counter, owned by booking-service.
--
-- Step toward the GA (general-admission) inventory model: tickets in a
-- category are fungible "total tickets available per category", not named
-- seats. Overselling protection moves OFF the synthetic-seat-row + unique-index
-- scheme (which caused the hot-event 409 storm: concurrent bookers randomly
-- collided on the same synthetic seat UUIDs even when capacity remained) and
-- ONTO a single atomic counter per category, decremented in the SAME
-- transaction as the booking insert.
--
-- Why this table lives in booking-service (not seat-service): the claim and
-- the booking row must commit atomically, and atomicity is only free inside
-- one database transaction. booking-service owns the booking, so it owns the
-- counter. seat-service remains the catalog (category name, price, capacity);
-- booking-service seeds `remaining` from seat-service's total_seats on first
-- booking for a category (see BookingService.persistBooking), accounting for
-- any pre-existing active bookings.
--
-- remaining >= 0 is enforced both by the CHECK here and by the guarded
-- decrement (UPDATE ... WHERE remaining >= :qty) so a claim can never push it
-- negative even under concurrent bookings — the row lock serialises them.

CREATE TABLE category_inventory (
    category_id UUID    PRIMARY KEY,
    remaining   INTEGER NOT NULL,
    CONSTRAINT chk_category_inventory_remaining_nonneg CHECK (remaining >= 0)
);
