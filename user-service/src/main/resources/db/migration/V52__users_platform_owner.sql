-- =============================================================================
-- V52: the seeded platform owner is a durable property of ONE row.
--
-- Owner decision (2026-10-21): there may be more than one SUPER_ADMIN account.
-- A second one ("deputy") is the same role name holding the same '*' wildcard,
-- so every service that checks the role name recognises it unchanged. What a
-- deputy may NOT do is promote or demote anyone — only the seeded platform
-- owner may — and nobody may touch the owner at all.
--
-- "Who is the owner" must therefore never be answered by comparing an email
-- with BOOTSTRAP_ADMIN_EMAIL at request time: that address is configuration, it
-- can be rotated, and a deputy holds the same role. It is this column instead.
-- DataInitializer stamps it on the row it seeds or adopts; nothing else in the
-- application writes it (the entity maps it insertable = false,
-- updatable = false, and the one writer is UserRepository.markPlatformOwner).
--
-- At most ONE row can hold it: uq_users_platform_owner is a partial unique
-- index over the TRUE rows only, so any number of FALSE rows is fine and a
-- second TRUE fails in the database whatever the application does.
--
-- Backfill: the account holding SUPER_ADMIN today becomes the owner — but only
-- if exactly one account holds it. With more than one, which of them is the
-- owner is an operator's decision, never a migration's, so this FAILS. Flyway
-- runs it in one transaction, so a failure leaves the database at V51 and the
-- previous user-service image keeps serving. With none (a fresh database), it
-- stamps nothing and DataInitializer stamps the row it seeds at boot.
--
-- Lock: ADD COLUMN with a constant default is metadata-only in Postgres 11+,
-- and the index build blocks writes to users for well under a second.
-- lock_timeout makes both fail fast, and retry on the next start, rather than
-- queue behind a long transaction.
-- =============================================================================
SET LOCAL lock_timeout = '5s';

ALTER TABLE users ADD COLUMN platform_owner BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX uq_users_platform_owner ON users ((platform_owner)) WHERE platform_owner;

DO $$
DECLARE
    holders INTEGER;
BEGIN
    SELECT count(DISTINCT user_id) INTO holders
      FROM user_roles
     WHERE role = 'SUPER_ADMIN';
    IF holders > 1 THEN
        RAISE EXCEPTION 'V52: % accounts hold SUPER_ADMIN, so the platform owner cannot be chosen automatically. Decide which account is the seeded owner (normally the one at BOOTSTRAP_ADMIN_EMAIL), remove SUPER_ADMIN from the others in user_roles, then deploy again. The others can be promoted back by the owner through POST /admin/super-admins/{userId}.', holders;
    END IF;
    UPDATE users
       SET platform_owner = TRUE
     WHERE id IN (SELECT user_id FROM user_roles WHERE role = 'SUPER_ADMIN');
END $$;
