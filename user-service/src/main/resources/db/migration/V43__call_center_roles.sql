-- Built-in customer-support roles: CALL_CENTER_AGENT, CALL_CENTER_SUPERVISOR
-- and FRAUD_DESK. They replace the old advice to compose "Call Centre" /
-- "Fraud Desk" roles at runtime: the console needs names it can offer in a role
-- picker on every cell, and code (StaffRoles.NAMED) needs names it can rely on.
--
-- A migration, not the API, because builtin = TRUE rows only ever come from
-- migrations (RoleAdminService.create always writes builtin = FALSE).
--
-- NEVER ADOPT AN EXISTING ROLE. If a role with one of these names already
-- exists — an operator followed the old advice — or accounts already hold the
-- name as a bare user_roles string (user_roles has no foreign key to roles),
-- this migration FAILS instead of merging. Adopting would:
--   * merge the operator's permissions into a role that then can never be
--     deleted (built-ins are undeletable);
--   * grant device-security permissions to every account already holding the
--     name, none of which was checked — including orphan user_roles strings; and
--   * take effect at once for any live token with an empty perms claim, which
--     JwtFilter re-resolves on EVERY request.
-- A failed migration rolls back (transactional DDL), the new pod never becomes
-- ready and the old pods keep serving. Run the pre-deploy query first (it is in
-- the PR and the rollout notes); every hit is an operator decision.

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM roles
              WHERE name IN ('CALL_CENTER_AGENT', 'CALL_CENTER_SUPERVISOR', 'FRAUD_DESK')) THEN
    RAISE EXCEPTION 'V43: a role named CALL_CENTER_AGENT, CALL_CENTER_SUPERVISOR or FRAUD_DESK already exists. Rename or delete it before deploying (see the pre-deploy query).';
  END IF;
  IF EXISTS (SELECT 1 FROM user_roles
              WHERE role IN ('CALL_CENTER_AGENT', 'CALL_CENTER_SUPERVISOR', 'FRAUD_DESK')) THEN
    RAISE EXCEPTION 'V43: accounts already hold CALL_CENTER_AGENT, CALL_CENTER_SUPERVISOR or FRAUD_DESK with no roles row. Remove those user_roles rows before deploying.';
  END IF;
END $$;

-- The permission rows the grants below reference. role_permissions carries a
-- foreign key to permissions, and PermissionCatalogInitializer (which mirrors
-- the code catalog into this table) runs AFTER Flyway — on a database that has
-- never booted a release defining these codes they do not exist yet. The
-- descriptions are overwritten from PermissionCatalog at the next boot.
INSERT INTO permissions (code, description) VALUES
  ('device-security:read',   'Look up a customer''s phones, blocks, references and sign-in history'),
  ('device-security:manage', 'Block, unlock, remove or reset a customer''s phone, and cancel open codes'),
  ('device-security:fraud',  'Ban a phone for fraud (including every account on it), lift such bans, and fraud-flag a customer')
ON CONFLICT (code) DO NOTHING;

-- roles.description is NOT NULL (V35). No ON CONFLICT: the DO block above has
-- proved none of these names exists, and a plain INSERT keeps it that way.
INSERT INTO roles (name, description, builtin, created_by) VALUES
  ('CALL_CENTER_AGENT',      'Call-center agent: looks customers up and performs routine support actions.', TRUE, 'flyway:V43'),
  ('CALL_CENTER_SUPERVISOR', 'Call-center supervisor: agent actions plus refunds, reversals and dispute decisions.', TRUE, 'flyway:V43'),
  ('FRAUD_DESK',             'Fraud desk: add-on role for bans, fraud holds and lifting them.', TRUE, 'flyway:V43');

-- The device-security permissions are the only support permissions enforced
-- today. The supervisor starts identical to the agent and diverges as
-- supervisor-only support actions land; FRAUD_DESK is an add-on, held together
-- with an agent or supervisor role. SUPER_ADMIN is deliberately NOT listed: it
-- holds '*', which expands to every code at token-mint time.
INSERT INTO role_permissions (role_name, permission_code) VALUES
  ('CALL_CENTER_AGENT',      'device-security:read'),
  ('CALL_CENTER_AGENT',      'device-security:manage'),
  ('CALL_CENTER_SUPERVISOR', 'device-security:read'),
  ('CALL_CENTER_SUPERVISOR', 'device-security:manage'),
  ('FRAUD_DESK',             'device-security:read'),
  ('FRAUD_DESK',             'device-security:fraud')
ON CONFLICT (role_name, permission_code) DO NOTHING;
