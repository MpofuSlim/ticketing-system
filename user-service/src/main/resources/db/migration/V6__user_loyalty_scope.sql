-- Loyalty scope for shop staff. SHOP_ADMIN and SHOP_USER users carry their
-- shop assignment directly on the users row (rather than via a separate join
-- table) because there's exactly one shop per staff member and the value is
-- read on every token issuance. loyalty_merchant_id is denormalized off the
-- shop so AuthService can emit both JWT claims without a cross-service hop
-- after the first login.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS loyalty_shop_id UUID;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS loyalty_merchant_id UUID;

CREATE INDEX IF NOT EXISTS idx_users_loyalty_shop ON users(loyalty_shop_id);
CREATE INDEX IF NOT EXISTS idx_users_loyalty_merchant ON users(loyalty_merchant_id);
