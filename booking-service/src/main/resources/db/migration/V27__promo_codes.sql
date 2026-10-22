-- Promo codes (V27): an organizer creates a code for one event; a buyer who
-- types it at POST /bookings gets a discount on each eligible ticket, on top
-- of the price tier the ticket is sold at.
--
--   discount_type   PERCENT (1-99 % off each ticket) or FIXED (an amount off
--                   each ticket). A ticket is never made free: a FIXED
--                   discount that would take a ticket to 0 or below is refused
--                   at booking.
--   max_uses        bookings (not CANCELLED) that may use the code; NULL = no
--                   limit. max_uses_per_customer the same per buyer (phone,
--                   else email).
--   starts_at /     the window it can be used in (UTC wall clock); NULL =
--   ends_at         open on that side.
--   category ids    promo_code_categories; no rows = every category of the event.
--
-- Uses are counted under the code's row lock (SELECT ... FOR UPDATE, taken
-- after the category stock locks), so max_uses is exact under concurrency. A
-- cancelled or expired booking stops counting, so its use is freed.
--
-- bookings.discount_amount is the booking's total discount and
-- booking_items.discount_amount each ticket's; a ticket's price_at_booking is
-- what was paid for it AFTER the discount, so every revenue sum is net
-- without change. NULL = no code used (every pre-V27 row).

CREATE TABLE promo_codes (
    id                     UUID PRIMARY KEY,
    event_id               UUID          NOT NULL,
    code                   VARCHAR(40)   NOT NULL,
    description            VARCHAR(200),
    discount_type          VARCHAR(10)   NOT NULL,
    discount_value         NUMERIC(10,2) NOT NULL,
    max_uses               INTEGER,
    max_uses_per_customer  INTEGER,
    starts_at              TIMESTAMP,
    ends_at                TIMESTAMP,
    active                 BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMP     NOT NULL,
    updated_at             TIMESTAMP,
    created_by             VARCHAR(255),
    updated_by             VARCHAR(255),
    CONSTRAINT uq_promo_codes_event_code UNIQUE (event_id, code),
    CONSTRAINT chk_promo_codes_type CHECK (discount_type IN ('PERCENT', 'FIXED')),
    CONSTRAINT chk_promo_codes_value CHECK (discount_value > 0
        AND (discount_type <> 'PERCENT' OR discount_value < 100)),
    CONSTRAINT chk_promo_codes_max_uses CHECK (max_uses IS NULL OR max_uses > 0),
    CONSTRAINT chk_promo_codes_max_per_customer CHECK (max_uses_per_customer IS NULL OR max_uses_per_customer > 0),
    CONSTRAINT chk_promo_codes_window CHECK (starts_at IS NULL OR ends_at IS NULL OR starts_at < ends_at),
    CONSTRAINT chk_promo_codes_code_upper CHECK (code = UPPER(code))
);

CREATE TABLE promo_code_categories (
    promo_code_id  UUID NOT NULL REFERENCES promo_codes (id) ON DELETE CASCADE,
    category_id    UUID NOT NULL,
    PRIMARY KEY (promo_code_id, category_id)
);

ALTER TABLE bookings
    ADD COLUMN promo_code_id    UUID,
    ADD COLUMN promo_code       VARCHAR(40),
    ADD COLUMN discount_amount  NUMERIC(10,2);

ALTER TABLE booking_items
    ADD COLUMN discount_amount  NUMERIC(10,2);

CREATE INDEX IF NOT EXISTS idx_bookings_promo_code ON bookings (promo_code_id) WHERE promo_code_id IS NOT NULL;
