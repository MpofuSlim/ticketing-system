-- Retire team members (owner's decision, 2026-11-05).
--
-- A team member was a TEAM_MEMBER account an organizer created with an email
-- and a temporary password, for a shared-device scanner app: a system user with
-- its own 2FA carve-out. Gate staff are now super-app scanners (event-service
-- V14): the organizer adds a phone number and a name, the person accepts in the
-- InnBucks app and scans there, with no account here. This release deletes the
-- endpoints, the role and its permissions.
--
-- What this migration does:
--   1. Refuses to run while any account still holds TEAM_MEMBER. None existed
--      when this was written; if one does, retiring it (remove the role,
--      deactivate the account, free its phone for the app) is a decision for an
--      operator, not something to do silently here. The failed migration rolls
--      back and the previous pod keeps serving.
--   2. Deletes the three team-members:* permissions. The FK cascades remove
--      every grant of them (EVENT_ORGANIZER held all three; any custom role
--      that held one loses it). They are TENANT codes, so removing them signs
--      nobody out. PermissionCatalogInitializer has no delete pass, which is why
--      the rows go here: a code the catalog no longer defines would classify
--      as PLATFORM and make EVENT_ORGANIZER look like a staff role.
--   3. Deletes the TEAM_MEMBER built-in role (it holds no grants; its
--      role_permissions would cascade anyway).
--   4. Rewords EVENT_ORGANIZER's description, only if it is still V35's text.
--
-- Kept as history, unmapped or unused: team_member_event_assignment,
-- users.created_by_organizer_uuid (still an ON DELETE RESTRICT FK, which is why
-- registration rejection still checks it), and audit rows of type
-- TEAM_MEMBER_DISABLED.

SET LOCAL lock_timeout = '5s';

DO $$
DECLARE
    holders INTEGER;
BEGIN
    SELECT COUNT(*) INTO holders FROM user_roles WHERE role = 'TEAM_MEMBER';
    IF holders > 0 THEN
        RAISE EXCEPTION 'V55: % account(s) still hold TEAM_MEMBER. Team members are retired: '
            'decide each account first (remove the role or deactivate it). List them with: '
            'SELECT u.id, u.email, u.phone_number, u.active FROM users u '
            'JOIN user_roles r ON r.user_id = u.id WHERE r.role = ''TEAM_MEMBER'';', holders;
    END IF;
END $$;

DELETE FROM permissions
WHERE code IN ('team-members:read', 'team-members:write', 'team-members:manage');

DELETE FROM roles WHERE name = 'TEAM_MEMBER';

UPDATE roles
SET description = 'Runs ticketed events; owns events, invoices and gate scanners.'
WHERE name = 'EVENT_ORGANIZER'
  AND description = 'Runs ticketed events; owns events, invoices and team members.';
