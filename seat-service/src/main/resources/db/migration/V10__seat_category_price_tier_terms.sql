-- Three more terms on a price tier (V9 shipped without them; V9 is never edited).
--
-- starts_at: the tier opens then (UTC, inclusive; NULL = at once). Tiers still
-- apply in tier_order, so the price is the first tier that has opened and not
-- yet closed.
--
-- max_per_customer: the tickets one buyer (by phone, else email) may hold at
-- the tier; tickets past it are priced at the next open tier. NULL = no cap.
--
-- refunds_reopen: whether a ticket refunded after payment (an admin reversal)
-- frees its place in the tier's quantity. Default TRUE, which is what every
-- existing tier already does. An unpaid hold that lapses always frees it.

ALTER TABLE seat_category_price_tiers
    ADD COLUMN starts_at        TIMESTAMP,
    ADD COLUMN max_per_customer INTEGER,
    ADD COLUMN refunds_reopen   BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE seat_category_price_tiers
    ADD CONSTRAINT chk_price_tier_window
        CHECK (starts_at IS NULL OR ends_at IS NULL OR starts_at < ends_at),
    ADD CONSTRAINT chk_price_tier_max_per_customer
        CHECK (max_per_customer IS NULL OR max_per_customer > 0);
