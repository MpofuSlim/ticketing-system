-- Price tiers: a seat category can sell at lower prices in phases before its
-- full price, e.g. "Early bird" 100.00 for the first 100 tickets or until
-- 8 September, then "Phase 1" 120.00 until 20 September, then price (150.00).
--
-- Tiers apply in tier_order (0-based): the first one open is the price. A tier
-- opens at starts_at (UTC, inclusive; NULL = at once) and closes at ends_at
-- (UTC, exclusive) or once quantity tickets are sold at it, whichever comes
-- first. It needs an end or a quantity, or it would never close and the later
-- tiers and the full price could never apply.
--
-- max_per_customer caps the tickets one buyer (by phone, else email) holds at
-- the tier; tickets past it are priced at the next open tier. refunds_reopen
-- (default TRUE) decides whether a ticket refunded after payment frees its
-- place in the quantity; an unpaid hold that lapses always frees it.
-- Nothing is rewritten when a tier closes: the price is worked out at read
-- time (seat-service CategoryPricing) and, for a booking, by booking-service
-- under the category's stock row lock, which is what makes "the first 100"
-- exact.
--
-- category_id is a plain column, not a JPA association: categories are only
-- ever soft-deleted, and nothing reads tiers except by category. The rule
-- that a tier's price is below seat_categories.price lives in the service,
-- because price may be edited after a tier has ended.
--
-- The tier_order unique constraint is DEFERRABLE INITIALLY DEFERRED so a
-- replacement that reorders tiers, or inserts a new tier at a position an old
-- one is leaving, is checked at commit rather than per statement.
CREATE TABLE seat_category_price_tiers (
    id          UUID           PRIMARY KEY,
    category_id UUID           NOT NULL REFERENCES seat_categories (id),
    tier_order  INTEGER        NOT NULL,
    name        VARCHAR(60)    NOT NULL,
    price       NUMERIC(10, 2) NOT NULL,
    starts_at   TIMESTAMP,
    ends_at     TIMESTAMP,
    quantity    INTEGER,
    max_per_customer INTEGER,
    refunds_reopen   BOOLEAN  NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP,
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    CONSTRAINT chk_price_tier_price_positive CHECK (price > 0),
    CONSTRAINT chk_price_tier_quantity_positive CHECK (quantity IS NULL OR quantity > 0),
    CONSTRAINT chk_price_tier_closes CHECK (ends_at IS NOT NULL OR quantity IS NOT NULL),
    CONSTRAINT chk_price_tier_window CHECK (starts_at IS NULL OR ends_at IS NULL OR starts_at < ends_at),
    CONSTRAINT chk_price_tier_max_per_customer CHECK (max_per_customer IS NULL OR max_per_customer > 0),
    CONSTRAINT uq_price_tier_order UNIQUE (category_id, tier_order) DEFERRABLE INITIALLY DEFERRED
);
