-- Defence-in-depth invariants and lookup indexes for seat-service.
-- The application enforces these too (atomic UPDATE with WHERE
-- available_seats > 0, status enum), but a CHECK at the DB layer makes
-- a corrupt counter impossible regardless of which code path writes it.

ALTER TABLE seat_categories
    ADD CONSTRAINT chk_seat_categories_available_nonneg
        CHECK (available_seats >= 0),
    ADD CONSTRAINT chk_seat_categories_available_lte_total
        CHECK (available_seats <= total_seats),
    ADD CONSTRAINT chk_seat_categories_total_nonneg
        CHECK (total_seats >= 0),
    ADD CONSTRAINT chk_seat_categories_price_nonneg
        CHECK (price >= 0);

ALTER TABLE seats
    ADD CONSTRAINT chk_seats_status
        CHECK (status IN ('AVAILABLE', 'LOCKED', 'BOOKED'));

CREATE INDEX IF NOT EXISTS idx_seat_categories_event_id
    ON seat_categories (event_id);

CREATE INDEX IF NOT EXISTS idx_seat_categories_event_active
    ON seat_categories (event_id) WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_seats_category_id
    ON seats (category_id);

CREATE INDEX IF NOT EXISTS idx_seats_category_status
    ON seats (category_id, status);
