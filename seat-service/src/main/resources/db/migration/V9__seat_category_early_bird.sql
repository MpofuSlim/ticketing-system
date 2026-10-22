-- Early-bird pricing: a category sells at early_bird_price until
-- early_bird_ends_at (a UTC wall clock, exclusive), then at price again.
-- Nothing is rewritten when the early bird ends: the price in force is worked
-- out at read time, so no scheduled job has to flip it at midnight.
--
-- Both columns are set together or not at all. price itself is untouched and
-- keeps meaning the full price. The rule that the early-bird price is BELOW
-- price is enforced in SeatCategoryService, not here: price can be edited
-- after the early bird has ended, and an expired offer must not block that.
-- NUMERIC(10, 2) and TIMESTAMP match price and the entity's LocalDateTime
-- under ddl-auto=validate. Additive and nullable: the previous image ignores
-- both columns.
ALTER TABLE seat_categories
    ADD COLUMN early_bird_price   NUMERIC(10, 2),
    ADD COLUMN early_bird_ends_at TIMESTAMP;

ALTER TABLE seat_categories
    ADD CONSTRAINT chk_seat_categories_early_bird_pair
        CHECK ((early_bird_price IS NULL) = (early_bird_ends_at IS NULL)),
    ADD CONSTRAINT chk_seat_categories_early_bird_price_positive
        CHECK (early_bird_price IS NULL OR early_bird_price > 0);
