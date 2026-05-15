-- Persist the Oradian linkage on the local customer profile. Set by
-- CustomerService.registerTier2 after the S2S call to Oradian middleware
-- (POST /internal/customers) succeeds. Lets us look up the customer's
-- banking record by Oradian's externalID / numeric clientID without going
-- back through msisdn each time, and gives reconciliation jobs a direct
-- handle on each side of the mapping.
--
-- Both columns nullable: tier-1 customers (and legacy rows from before this
-- migration) have no Oradian record yet.
--
-- Partial unique indexes enforce the 1:1 mapping at the DB level while
-- still allowing multiple NULLs (tier-1 customers all coexist). A regular
-- UNIQUE constraint would treat NULLs as distinct in Postgres, but partial
-- indexes make the intent explicit and skip indexing tier-1 rows entirely.

ALTER TABLE customer_profiles
    ADD COLUMN IF NOT EXISTS oradian_external_id VARCHAR(64);

ALTER TABLE customer_profiles
    ADD COLUMN IF NOT EXISTS oradian_client_id BIGINT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_profile_oradian_external_id
    ON customer_profiles(oradian_external_id)
    WHERE oradian_external_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_profile_oradian_client_id
    ON customer_profiles(oradian_client_id)
    WHERE oradian_client_id IS NOT NULL;
