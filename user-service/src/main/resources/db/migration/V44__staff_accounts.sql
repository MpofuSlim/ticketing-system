-- Staff accounts with invites (Ask A).
--
-- A staff account is created by an administrator (POST /admin/staff), never by
-- self-registration, and proves its mailbox by redeeming a single-use invite
-- link before it can sign in. Three schema facts follow:
--
--   1. users.phone_number becomes NULLABLE. A staff member signs in with their
--      InnBucks email only; a sign-in phone on a staff account is a takeover
--      path (whoever holds the number could reset the password). The number
--      they give us is a CONTACT number and lives on staff_profiles. Postgres
--      treats NULLs as distinct in uk_users_phone_country (phone_number,
--      home_country), so any number of staff rows without a phone never collide.
--   2. users.email_verified_at records that the address was proven by redeeming
--      an invite, and users.last_sign_in_at when a session was last issued.
--      Both start NULL: nothing before this migration proved a mailbox, and
--      AUTH_LOGIN_SUCCESS is written before the second factor so it is not a
--      valid backfill source.
--   3. staff_profiles (one row per staff account) and staff_invites (the
--      single-use links, stored as SHA-256 hashes only).
--
-- Additive: the previous image boots against it (Hibernate validate ignores
-- nullability) and never reads the new tables.

SET LOCAL lock_timeout = '5s';

ALTER TABLE users ALTER COLUMN phone_number DROP NOT NULL;
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMP NULL;
ALTER TABLE users ADD COLUMN last_sign_in_at   TIMESTAMP NULL;

CREATE TABLE staff_profiles (
  user_id            BIGINT      PRIMARY KEY REFERENCES users(id),
  contact_phone      VARCHAR(20) NULL,              -- E.164; display/contact only, never a sign-in identifier
  adopted            BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at         TIMESTAMP   NOT NULL,
  invite_accepted_at TIMESTAMP   NULL
);
-- country lives on users.country and the creator on users.created_by (V33): one home each

CREATE TABLE staff_invites (
  id               BIGSERIAL    PRIMARY KEY,
  user_id          BIGINT       NOT NULL REFERENCES users(id),
  token_hash       VARCHAR(64)  NOT NULL UNIQUE,
  sent_to_email    VARCHAR(254) NOT NULL,
  created_at       TIMESTAMP    NOT NULL,
  created_by_email VARCHAR(254) NOT NULL,
  expires_at       TIMESTAMP    NOT NULL,
  used_at          TIMESTAMP    NULL,
  revoked_at       TIMESTAMP    NULL,
  revoked_reason   VARCHAR(32)  NULL,     -- SUPERSEDED | DEACTIVATED | REACTIVATED | EMAIL_CHANGED
  delivery_status  VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  delivered_at     TIMESTAMP    NULL
);
CREATE INDEX ix_staff_invites_user_live    ON staff_invites(user_id) WHERE used_at IS NULL AND revoked_at IS NULL;
CREATE INDEX ix_staff_invites_user_created ON staff_invites(user_id, created_at);
