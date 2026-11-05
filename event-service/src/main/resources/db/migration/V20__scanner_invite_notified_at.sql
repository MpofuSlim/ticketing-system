-- V20: when the invite message went to a gate scanner's number.
--
-- Until now adding a scanner sent nothing: the invite appeared only when the
-- person opened the InnBucks app's gate screen, and nobody told them to
-- (owner's decision, 2026-11-05: message them). The message names the event,
-- and a scanner is added before any event is assigned, so it goes out at the
-- first assignment while the scanner is INVITED; inviting a scanner who
-- declined again sends it once more.
--
-- invite_notified_at is that "once": the first assignment claims it with a
-- conditional UPDATE (NULL -> now), so two assignments at once send one
-- message; inviting again clears it. NULL on every existing row, so a scanner
-- already waiting gets the message at its next assignment.
--
-- A nullable column with no default: metadata only in Postgres, no rewrite.
-- lock_timeout so a busy moment fails the migration rather than queueing
-- scanner reads; the old pod serves on and a retry succeeds.

SET LOCAL lock_timeout = '5s';

ALTER TABLE event_scanners ADD COLUMN invite_notified_at TIMESTAMP;
