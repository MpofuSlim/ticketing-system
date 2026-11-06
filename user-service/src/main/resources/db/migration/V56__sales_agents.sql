-- Sales agents (owner decisions, 2026-11-06).
--
-- An organizer adds a sales agent to their event team in event-service (the
-- super-app scanner rows, with can_sell). The agent sells from the InnBucks app
-- with their ordinary CUSTOMER session, and from the Foundry portal with an
-- email, a password and 2FA. This migration is user-service's half:
--
--   1. The SALES_AGENT built-in role. It grants NO permission: what an agent may
--      sell is decided live by event-service's team membership on every sale.
--      Its one job here is to make MfaPolicy treat a portal agent as a system
--      user (any non-CUSTOMER role), so the first portal sign-in forces 2FA
--      enrolment. A phone-proof (super app) session is still minted CUSTOMER
--      only, whatever the account holds.
--
--   2. sales_agent_invites: the single-use link that binds portal sign-in to the
--      InnBucks account on the agent's phone. Only the SHA-256 of the SAI- token
--      is stored. Consumed by one conditional UPDATE that also names the account
--      it was accepted into, so the CHECK below holds from that statement on.
--
-- NEVER ADOPT AN EXISTING ROLE (the V43 rule). If a role named SALES_AGENT
-- already exists (an operator composed one at runtime), or accounts hold the
-- name as a bare user_roles string (user_roles has no foreign key to roles),
-- this migration FAILS instead of merging: adopting would turn an operator's
-- role, with whatever permissions they gave it, into an undeletable built-in,
-- and hand it to holders nobody checked. A failed migration rolls back and the
-- previous pod keeps serving. Pre-deploy check:
--   SELECT name FROM roles WHERE name = 'SALES_AGENT';
--   SELECT COUNT(*) FROM user_roles WHERE role = 'SALES_AGENT';

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM roles WHERE name = 'SALES_AGENT') THEN
    RAISE EXCEPTION 'V56: a role named SALES_AGENT already exists. Rename or delete it before deploying '
      '(SELECT name, builtin, created_by FROM roles WHERE name = ''SALES_AGENT'';).';
  END IF;
  IF EXISTS (SELECT 1 FROM user_roles WHERE role = 'SALES_AGENT') THEN
    RAISE EXCEPTION 'V56: accounts already hold SALES_AGENT with no roles row. Remove those user_roles rows '
      'before deploying (SELECT user_id FROM user_roles WHERE role = ''SALES_AGENT'';).';
  END IF;
END $$;

-- roles.description is NOT NULL (V35). No ON CONFLICT: the DO block above has
-- proved the name is free. No role_permissions row, on purpose.
INSERT INTO roles (name, description, builtin, created_by) VALUES
  ('SALES_AGENT', 'Sells tickets for an organizer at the box office; what they may sell is set by the organizer''s event team.', TRUE, 'flyway:V56');

CREATE TABLE sales_agent_invites (
  id                 BIGSERIAL    PRIMARY KEY,
  -- SHA-256 hex of the raw SAI- token. Never the token itself.
  token_hash         VARCHAR(64)  NOT NULL,
  -- event-service's team row (event_scanners.scanner_id): a new invite for the
  -- same row revokes the earlier open ones.
  team_member_id     UUID         NOT NULL,
  organizer_uuid     UUID         NOT NULL,
  organizer_name     VARCHAR(80)  NULL,
  -- E.164, the number the organizer typed for the team row.
  phone_number       VARCHAR(32)  NOT NULL,
  email              VARCHAR(254) NOT NULL,
  expires_at         TIMESTAMP    NOT NULL,
  used_at            TIMESTAMP    NULL,
  revoked_at         TIMESTAMP    NULL,
  created_at         TIMESTAMP    NOT NULL,
  -- users.user_uuid of the account the invite was accepted into. No foreign
  -- key: the row is history, and an approved account is never deleted.
  account_user_uuid  UUID         NULL,
  CONSTRAINT uq_sales_agent_invites_token_hash UNIQUE (token_hash),
  CONSTRAINT chk_sales_agent_invites_use_binds_account
    CHECK ((used_at IS NULL) = (account_user_uuid IS NULL))
);

CREATE INDEX idx_sales_agent_invites_team_member ON sales_agent_invites (team_member_id);
