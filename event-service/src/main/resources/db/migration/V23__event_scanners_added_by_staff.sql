-- V23: a gate scanner InnBucks staff added to an organizer's team.
--
-- Owner's decision (2026-11-13): SUPER_ADMIN and PRODUCT_MANAGER may add and
-- manage an organizer's GATE SCANNERS (add, assign to the organizer's events,
-- switch off/on, invite again). Sales agents, their terms and portal logins
-- stay the organizer's own. The organizer sees "Added by InnBucks" on a member
-- staff added; no message is sent to the organizer.
--
-- added_by_staff is that marker, recorded explicitly at the add. created_by
-- holds the caller's name, and who is staff cannot be told from a name.
-- FALSE on every existing row: until now only organizers could add.
--
-- A NOT NULL column with a constant default: metadata only in Postgres, no
-- rewrite. lock_timeout so a busy moment fails the migration rather than
-- queueing scanner reads; the old pod serves on and a retry succeeds.

SET LOCAL lock_timeout = '5s';

ALTER TABLE event_scanners ADD COLUMN added_by_staff BOOLEAN NOT NULL DEFAULT FALSE;
