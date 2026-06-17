-- Stable cross-service user identifier + event-organizer team-member relation.
--
-- Why a separate UUID column when users already has a BIGINT PK?
-- The BIGINT id is the local PK (and stays so — every internal FK in
-- user-service keeps using it). But the BIGINT leaks a monotonic count
-- to anyone who sees it, and it's the odd one out in a fleet whose other
-- cross-service identifiers (eventId, loyaltyShopId, loyaltyMerchantId,
-- bookingId) are all UUIDs. user_uuid is the stable, unguessable identifier
-- we expose over the wire and as JWT claims, so other services don't have
-- to round-trip to user-service via email to derive a user reference.
--
-- Backfill is one-shot, idempotent: rows that already have a user_uuid (none,
-- since the column doesn't exist yet) are skipped; new rows get one from
-- the DEFAULT clause.

ALTER TABLE users
    ADD COLUMN user_uuid UUID DEFAULT gen_random_uuid();

UPDATE users SET user_uuid = gen_random_uuid() WHERE user_uuid IS NULL;

ALTER TABLE users ALTER COLUMN user_uuid SET NOT NULL;

CREATE UNIQUE INDEX uq_users_user_uuid ON users(user_uuid);

-- Event-organizer team-member relation. The FK points at user_uuid (not
-- users.id) so the column type matches the JWT claim (organizerUuid) the
-- caller carries and so foreign-key checks line up with the value the
-- application reads from authentication, never via a per-request lookup.
--
-- ON DELETE RESTRICT: a soft-delete model uses User.active=false +
-- token_version++ to disable team members rather than removing the row, so
-- the FK never actually fires the RESTRICT in normal operation. The
-- constraint is a backstop against an accidental hard DELETE on an
-- organizer row that would otherwise orphan their team's audit trail.
ALTER TABLE users
    ADD COLUMN created_by_organizer_uuid UUID REFERENCES users(user_uuid) ON DELETE RESTRICT;

CREATE INDEX idx_users_created_by_organizer_uuid
    ON users(created_by_organizer_uuid)
    WHERE created_by_organizer_uuid IS NOT NULL;
