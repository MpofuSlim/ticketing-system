-- Customer support for the marketplace and loyalty products: grant the new
-- support permissions to the V43 call-center built-ins.
--
-- The codes are ENFORCED ELSEWHERE — marketplace-service (/marketplace/support/**)
-- and loyalty-service (/loyalty/support/**) read the token's perms claim and
-- gate on hasAuthority. They are defined in PermissionCatalog because it is the
-- fleet's one permission vocabulary and role_permissions can only reference a
-- code the permissions table holds.
--
-- Only the V43 built-ins are touched, as V43 requires of any later grant
-- migration. They exist (V43 created them and built-ins can be neither deleted
-- nor renamed), so there is nothing to adopt and no collision to check.
-- SUPER_ADMIN is deliberately NOT listed: it holds '*', which expands to every
-- code at token-mint time. FRAUD_DESK is an add-on held WITH an agent or
-- supervisor role, so it gains nothing here.
--
-- A GRANT does not bump anyone's tokenVersion: an agent picks the new codes up
-- at their next sign-in or refresh, which re-derives perms from the live role.

-- The permission rows the grants reference. role_permissions carries a foreign
-- key to permissions, and PermissionCatalogInitializer (which mirrors the code
-- catalog into this table) runs AFTER Flyway — on a database that has never
-- booted a release defining these codes they do not exist yet. The descriptions
-- are overwritten from PermissionCatalog at the next boot.
INSERT INTO permissions (code, description) VALUES
  ('marketplace-support:read',      'Look up marketplace buyers, orders, parcels and sellers, with their support notes and messages'),
  ('marketplace-support:manage',    'Routine marketplace support: notes, resend notices and collection codes, cancel an unpaid order, open a dispute for a buyer'),
  ('marketplace-support:supervise', 'Cancel a paid, undispatched parcel for a buyer (refund queued) and review every agent''s marketplace support activity'),
  ('loyalty-support:read',          'Look up a loyalty customer across every tenant: balance, history, vouchers, orders, notes and messages'),
  ('loyalty-support:manage',        'Routine loyalty support: notes, resend a voucher to its holder, sign a customer out everywhere'),
  ('loyalty-support:supervise',     'Adjust or reverse a customer''s points, unblock a membership, and review every agent''s loyalty support activity'),
  ('customer-messages:send',        'Type and send an SMS or WhatsApp message to a customer on record from a support screen')
ON CONFLICT (code) DO NOTHING;

-- The agent looks up, acts routinely and messages; the supervisor holds the
-- agent's grants plus the tier that moves money or overrides a block, and
-- reviews every agent's activity — the split V43's role descriptions promised
-- ("agent actions plus refunds, reversals and dispute decisions"). Marketplace
-- DISPUTE decisions are the exception and stay with SUPER_ADMIN/finance (owner's
-- decision, 2026-09-30): deciding one as a refund records a transfer reference,
-- i.e. asserts money already left, which a call-center supervisor cannot know.
INSERT INTO role_permissions (role_name, permission_code) VALUES
  ('CALL_CENTER_AGENT',      'marketplace-support:read'),
  ('CALL_CENTER_AGENT',      'marketplace-support:manage'),
  ('CALL_CENTER_AGENT',      'loyalty-support:read'),
  ('CALL_CENTER_AGENT',      'loyalty-support:manage'),
  ('CALL_CENTER_AGENT',      'customer-messages:send'),
  ('CALL_CENTER_SUPERVISOR', 'marketplace-support:read'),
  ('CALL_CENTER_SUPERVISOR', 'marketplace-support:manage'),
  ('CALL_CENTER_SUPERVISOR', 'marketplace-support:supervise'),
  ('CALL_CENTER_SUPERVISOR', 'loyalty-support:read'),
  ('CALL_CENTER_SUPERVISOR', 'loyalty-support:manage'),
  ('CALL_CENTER_SUPERVISOR', 'loyalty-support:supervise'),
  ('CALL_CENTER_SUPERVISOR', 'customer-messages:send')
ON CONFLICT (role_name, permission_code) DO NOTHING;
