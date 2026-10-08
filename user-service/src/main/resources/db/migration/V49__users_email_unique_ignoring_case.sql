-- =============================================================================
-- V49: one account per email address, whatever its letter case.
--
-- Owner decision (2026-10-08): GClerkson@innbucks.co.zw and
-- gclerkson@innbucks.co.zw must never both exist. uk_users_email (V1) is
-- case-sensitive, so until now only the application stood in the way: the
-- existsByEmailIgnoreCase checks at registration and staff create (and, from
-- this release, tier-2, shop-staff and team-member create). Two requests racing
-- past those checks, or a writer that forgot one, could still store both.
--
-- This replaces V48's plain idx_users_email_upper with a UNIQUE index on the
-- same expression. Spring Data's IgnoreCase compares UPPER(email) = UPPER(?),
-- so the one index keeps serving those lookups and now also refuses a second
-- spelling of an address already held. Accounts with no email (NULL) are
-- unaffected; any number of them may exist.
--
-- FAILS rather than guesses when the table already holds a letter-case pair:
-- which of two accounts keeps the address is an operator's decision (the PR
-- lists the read-only query that finds them), never a migration's. Flyway runs
-- this in one transaction, so a failure leaves the database at V48 and the
-- previous user-service image keeps serving.
--
-- Lock: a plain CREATE UNIQUE INDEX blocks writes to users while it builds
-- (well under a second for this table). lock_timeout makes it fail fast, and
-- retry on the next start, rather than queue behind a long transaction.
-- =============================================================================
SET LOCAL lock_timeout = '5s';

DO $$
DECLARE
    clashes INTEGER;
BEGIN
    SELECT count(*) INTO clashes
      FROM (SELECT 1
              FROM users
             WHERE email IS NOT NULL
             GROUP BY UPPER(email)
            HAVING count(*) > 1) AS pairs;
    IF clashes > 0 THEN
        RAISE EXCEPTION 'V49: % email address(es) are held by more than one account in different letter case. Resolve them before deploying (the PR lists the query that finds them).', clashes;
    END IF;
END $$;

CREATE UNIQUE INDEX uq_users_email_upper ON users (UPPER(email));

DROP INDEX IF EXISTS idx_users_email_upper;
