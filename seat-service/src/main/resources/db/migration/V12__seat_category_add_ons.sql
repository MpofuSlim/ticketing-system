-- Add-ons: extras a buyer can add to each ticket of a category, each with its
-- own price, e.g. "Parking pass" 5.00 on VIP. At most one of each per ticket.
--
-- max_quantity is an optional limit on how many may sell; the service refuses
-- one above the category's total_seats (100 VIP tickets can never carry 200
-- parking passes), and total_seats cannot be edited after creation. NULL = no
-- limit beyond one per ticket. booking-service counts the add-ons held or sold
-- under the category's stock row lock, which is what makes the limit exact.
--
-- Bookings keep each add-on's name and price as bought (booking_items.add_ons
-- in booking-service), so editing or removing one here never restates a sale.
-- ON DELETE CASCADE only matters to tests that hard-delete categories:
-- categories are only ever soft-deleted.
CREATE TABLE seat_category_add_ons (
    id            UUID           PRIMARY KEY,
    category_id   UUID           NOT NULL REFERENCES seat_categories (id) ON DELETE CASCADE,
    position      INTEGER        NOT NULL,
    name          VARCHAR(60)    NOT NULL,
    description   VARCHAR(200),
    price         NUMERIC(10, 2) NOT NULL,
    max_quantity  INTEGER,
    created_at    TIMESTAMP      NOT NULL,
    updated_at    TIMESTAMP,
    created_by    VARCHAR(255),
    updated_by    VARCHAR(255),
    CONSTRAINT chk_add_on_price CHECK (price >= 0),
    CONSTRAINT chk_add_on_max_quantity CHECK (max_quantity IS NULL OR max_quantity > 0)
);

CREATE INDEX idx_seat_category_add_ons_category ON seat_category_add_ons (category_id, position);
