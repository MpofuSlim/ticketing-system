-- Extend the tier CHECK constraint to cover tier 4.
--
-- The original V2 constraint capped registration_tier at 3, but the customer
-- onboarding flow grew a fourth tier (CustomerService.registerTier4) that
-- writes registrationTier=4 on KYC completion. Every tier-4 attempt was
-- failing with a CHECK violation at insert time — the entity, the JWT
-- claim handling, and MAX_TIER in the service all already understood 4, so
-- only the DB invariant was out of step.

ALTER TABLE customer_profiles
    DROP CONSTRAINT IF EXISTS chk_customer_profiles_tier;

ALTER TABLE customer_profiles
    ADD CONSTRAINT chk_customer_profiles_tier
        CHECK (registration_tier BETWEEN 1 AND 4);
