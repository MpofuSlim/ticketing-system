-- Provider-agnostic core-banking linkage, phase 1 of the per-cell provider
-- split (KE -> Oradian, ZW -> Veengu). The oradian_* columns from V10 stay —
-- they keep being written in lockstep on Oradian cells so existing tooling
-- and reconciliation jobs don't break — but the durable, provider-neutral
-- pair every cell writes from now on is (core_banking_provider,
-- core_banking_profile_id):
--   ORADIAN -> profile id = Oradian externalID (mirrors oradian_external_id)
--   VEENGU  -> profile id = Veengu individual profile id (oradian_* stay null)
--
-- Both columns nullable: tier-1 customers have no core-banking record yet.

ALTER TABLE customer_profiles
    ADD COLUMN IF NOT EXISTS core_banking_provider VARCHAR(16);

ALTER TABLE customer_profiles
    ADD COLUMN IF NOT EXISTS core_banking_profile_id VARCHAR(64);

-- Backfill: every row that already has an Oradian linkage was created by an
-- Oradian cell. Idempotent — re-runs only touch rows still unset.
UPDATE customer_profiles
SET core_banking_provider  = 'ORADIAN',
    core_banking_profile_id = oradian_external_id
WHERE oradian_external_id IS NOT NULL
  AND core_banking_provider IS NULL;

-- Same 1:1 guarantee the V10 partial indexes give the oradian columns: a
-- provider-side profile maps to at most one local customer, while tier-1
-- rows (all-NULL) coexist freely. Scoped per provider so an Oradian
-- externalID and a Veengu id that happen to collide lexically can't trip
-- each other.
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_profile_core_banking_ref
    ON customer_profiles(core_banking_provider, core_banking_profile_id)
    WHERE core_banking_profile_id IS NOT NULL;
