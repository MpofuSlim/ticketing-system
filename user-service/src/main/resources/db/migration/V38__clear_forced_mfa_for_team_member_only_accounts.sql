-- TEAM_MEMBER 2FA exemption — clean up the FORCE-enrolled population.
--
-- MfaPolicy now exempts TEAM_MEMBER from forced enrolment (gate staff:
-- scanner operators on borrowed phones, whose whole authority is redeeming
-- one organizer's tickets). The policy change alone only stops FUTURE forced
-- enrolments: shouldChallenge still honours mfa_enabled, so a team member
-- who was already force-enrolled under the old everything-but-CUSTOMER
-- policy would stay challenged on every login — the exact complaint the
-- exemption exists to fix — and one who lost the authenticator would be
-- locked out entirely.
--
-- Clearing it here is safe ONLY because of scope: there is no self-service
-- opt-IN enrolment path (enrolment tokens are minted exclusively in the
-- forced-enrolment login branch), so on an account whose every role is in
-- the exempt set {CUSTOMER, TEAM_MEMBER}, mfa_enabled = TRUE can only mean
-- "force-enrolled under the previous policy" — never a factor somebody
-- chose. Accounts holding ANY other role are untouched: for them MFA is
-- still mandatory, and stripping a factor from, say, an organizer who also
-- scans at the gate would widen the exemption the allow-list in MfaPolicy
-- deliberately refuses to widen.
--
-- Writes mirror MfaService.disable exactly: flag off, secret wiped, backup
-- codes deleted, device trust revoked (a standing trusted-device bypass for
-- a factor that no longer exists is a loose end). No audit_events rows are
-- written — those are HMAC-chained by the application and cannot be minted
-- from SQL; this migration file is the audit record of the bulk action.

CREATE TEMPORARY TABLE tmp_forced_mfa_team_members ON COMMIT DROP AS
SELECT u.id
FROM users u
WHERE u.mfa_enabled = TRUE
  AND EXISTS (SELECT 1
              FROM user_roles r
              WHERE r.user_id = u.id
                AND r.role = 'TEAM_MEMBER')
  AND NOT EXISTS (SELECT 1
                  FROM user_roles r
                  WHERE r.user_id = u.id
                    AND r.role NOT IN ('CUSTOMER', 'TEAM_MEMBER'));

DELETE FROM mfa_backup_codes
WHERE user_id IN (SELECT id FROM tmp_forced_mfa_team_members);

UPDATE devices
SET mfa_trust_token_hash = NULL,
    mfa_trusted_until    = NULL
WHERE user_id IN (SELECT id FROM tmp_forced_mfa_team_members);

UPDATE users
SET mfa_enabled = FALSE,
    mfa_secret  = NULL
WHERE id IN (SELECT id FROM tmp_forced_mfa_team_members);
