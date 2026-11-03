-- Suspending and reinstating one service (product) of a business, by
-- SUPER_ADMIN (owner decisions, 2026-11-03). organization_products.status has
-- held ACTIVE / SUSPENDED since V39; nothing wrote SUSPENDED until now. These
-- columns say who suspended it, when and why, and are cleared on reinstate.
--
-- suspended_loyalty_merchants: the loyalty merchants a business owned when its
-- loyalty service was suspended, read from InnRewards AT SUSPEND TIME so no
-- sign-in has to ask InnRewards. While a merchant is listed here its cashiers
-- (shop staff, users.loyalty_merchant_id) are minted no shop role and its till
-- keys (pos_api_keys) are refused. Deleted with the business (registration
-- rejection) and on reinstate.
--
-- Three nullable columns with no default and a new empty table: catalog-only,
-- no rewrite. lock_timeout bounds the brief lock on organization_products.
SET LOCAL lock_timeout = '10s';

ALTER TABLE organization_products ADD COLUMN suspended_at TIMESTAMP;
ALTER TABLE organization_products ADD COLUMN suspended_by VARCHAR(254);
ALTER TABLE organization_products ADD COLUMN suspension_reason VARCHAR(500);

CREATE TABLE suspended_loyalty_merchants (
    merchant_id      UUID      PRIMARY KEY,
    organization_id  UUID      NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    suspended_at     TIMESTAMP NOT NULL
);
CREATE INDEX idx_suspended_loyalty_merchants_org ON suspended_loyalty_merchants (organization_id);
