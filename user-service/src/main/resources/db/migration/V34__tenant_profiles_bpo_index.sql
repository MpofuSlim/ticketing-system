-- Supports TenantProfileRepository.existsByBpoNumber — the duplicate-BPO guard
-- on business registration (AuthService.register), which rejects a second
-- business claiming an already-registered BPO / tax number.
--
-- Non-unique on purpose: uniqueness is enforced in the service layer. A DB
-- UNIQUE constraint is deferred because any pre-existing duplicate bpo_number in
-- the data would fail this migration; that would need a de-dup pass first. This
-- index just keeps the existence lookup cheap. bpo_number is NULL for every
-- non-business account (many nulls), which a plain btree index handles fine.
CREATE INDEX IF NOT EXISTS idx_tenant_profiles_bpo_number
    ON tenant_profiles (bpo_number);
