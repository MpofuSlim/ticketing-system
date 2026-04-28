-- Defence-in-depth invariants and lookup indexes for user-service.

ALTER TABLE users
    ADD CONSTRAINT chk_users_role
        CHECK (role IS NULL OR role IN (
            'SYSTEM_MANAGER', 'TENANT', 'MERCHANT_ADMIN',
            'SHOP_ADMIN', 'SHOP_USER', 'CUSTOMER', 'ADMIN'));

ALTER TABLE customer_profiles
    ADD CONSTRAINT chk_customer_profiles_tier
        CHECK (registration_tier BETWEEN 1 AND 3),
    ADD CONSTRAINT chk_customer_profiles_gender
        CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER'));

ALTER TABLE otps
    ADD CONSTRAINT chk_otps_failed_attempts_nonneg
        CHECK (failed_attempts >= 0);

ALTER TABLE otp_retry_attempts
    ADD CONSTRAINT chk_otp_retry_attempt_count_nonneg
        CHECK (attempt_count >= 0);

-- Lookup indexes (uniques are already enforced by table constraints).
CREATE INDEX IF NOT EXISTS idx_devices_user_id        ON devices (user_id);
CREATE INDEX IF NOT EXISTS idx_revoked_tokens_expires ON revoked_tokens (expires_at);
CREATE INDEX IF NOT EXISTS idx_otps_expires           ON otps (expires_at);
CREATE INDEX IF NOT EXISTS idx_pending_registrations_expires
    ON pending_registrations (expires_at);
