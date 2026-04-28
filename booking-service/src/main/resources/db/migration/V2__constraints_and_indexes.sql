-- Defence-in-depth invariants and lookup indexes for booking-service.

ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_total_amount_nonneg
        CHECK (total_amount >= 0),
    ADD CONSTRAINT chk_bookings_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED'));

ALTER TABLE booking_items
    ADD CONSTRAINT chk_booking_items_price_nonneg
        CHECK (price_at_booking >= 0),
    ADD CONSTRAINT chk_booking_items_seat_number_pos
        CHECK (seat_number >= 0);

CREATE INDEX IF NOT EXISTS idx_bookings_user_email
    ON bookings (user_email);

CREATE INDEX IF NOT EXISTS idx_bookings_event_id
    ON bookings (event_id);

CREATE INDEX IF NOT EXISTS idx_bookings_status
    ON bookings (status);

CREATE INDEX IF NOT EXISTS idx_booking_items_booking_id
    ON booking_items (booking_id);

CREATE INDEX IF NOT EXISTS idx_booking_items_seat_id
    ON booking_items (seat_id);
