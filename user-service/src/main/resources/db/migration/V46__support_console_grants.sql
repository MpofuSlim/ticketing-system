-- Customer support (Ask C, PR 2): the Foundry-console support permissions, and
-- their grants to the call-center built-ins V43 seeded.
--
--   CALL_CENTER_AGENT      += support-console:read, support-console:manage
--   CALL_CENTER_SUPERVISOR += support-console:read, support-console:manage,
--                             support-console:mfa:reset, support-staff-targets:manage
--
-- Rules every support grant migration follows (design §3.3):
--   * the permissions rows go in FIRST — role_permissions has a foreign key to
--     permissions, and PermissionCatalogInitializer (which mirrors the code
--     catalog into that table) only runs AFTER Flyway;
--   * grants go ONLY to the V43 built-ins, and the DO block below proves each
--     target exists with builtin = TRUE — a same-named runtime role (the old
--     "compose Call Centre at runtime" advice) must never be handed platform
--     authority by a migration nobody reviewed against it;
--   * never a TENANT built-in, never a wildcard-reserved code, and never
--     SUPER_ADMIN, which holds '*' and picks every new code up at token-mint
--     time (enumerating would lock the platform owner out of later codes).
--
-- Migrations bypass RoleGrantGuard. That is safe here because every holder of a
-- V43 role passed the guard when the role was assigned, and the mint-time
-- staff-eligibility filter is the backstop for any that did not.

DO $$
DECLARE
  missing TEXT;
BEGIN
  SELECT string_agg(r.name, ', ') INTO missing
    FROM (VALUES ('CALL_CENTER_AGENT'), ('CALL_CENTER_SUPERVISOR')) AS r(name)
   WHERE NOT EXISTS (SELECT 1 FROM roles x WHERE x.name = r.name AND x.builtin = TRUE);
  IF missing IS NOT NULL THEN
    RAISE EXCEPTION 'V46: % is missing or not a built-in role (V43 seeds them with builtin = TRUE). Refusing to grant support permissions to it.', missing;
  END IF;
END $$;

INSERT INTO permissions (code, description) VALUES
  ('support-console:read',         'Look customers up in customer support and see their Foundry console account'),
  ('support-console:manage',       'Unlock a Foundry console account and send it a password-reset code from customer support'),
  ('support-console:mfa:reset',    'Reset a Foundry console account''s two-factor sign-in from customer support (supervisor)'),
  ('support-staff-targets:manage', 'Act in customer support when a lookup matches an InnBucks staff account (supervisor)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_name, permission_code) VALUES
  ('CALL_CENTER_AGENT',      'support-console:read'),
  ('CALL_CENTER_AGENT',      'support-console:manage'),
  ('CALL_CENTER_SUPERVISOR', 'support-console:read'),
  ('CALL_CENTER_SUPERVISOR', 'support-console:manage'),
  ('CALL_CENTER_SUPERVISOR', 'support-console:mfa:reset'),
  ('CALL_CENTER_SUPERVISOR', 'support-staff-targets:manage')
ON CONFLICT (role_name, permission_code) DO NOTHING;
