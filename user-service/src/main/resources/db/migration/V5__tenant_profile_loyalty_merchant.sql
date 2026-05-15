-- Adds a link from a tenant's profile to its loyalty-service merchant. The
-- value is included as the merchantId JWT claim so loyalty endpoints can
-- scope writes to the caller's merchant without trusting the request body.
ALTER TABLE tenant_profiles
    ADD COLUMN IF NOT EXISTS loyalty_merchant_id UUID;
