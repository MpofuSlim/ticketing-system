-- Drops the loyalty_merchant_id column added in V5. The auto-resolve logic that
-- read/wrote it (AuthService -> LoyaltyServiceClient.findMerchantIdByAdminEmail ->
-- cache to TenantProfile) was removed because in practice the column was always
-- null at the time MERCHANT_ADMIN tokens were minted. Shop staff carry their
-- merchant binding directly on the users row (V6) and that's the only path
-- now used for JWT scope.
ALTER TABLE tenant_profiles
    DROP COLUMN IF EXISTS loyalty_merchant_id;
