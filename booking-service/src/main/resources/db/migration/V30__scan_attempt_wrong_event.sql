-- Allow the WRONG_EVENT scan outcome.
--
-- A super-app scanner scans for the event they picked on screen (event-service
-- V14 scanners), and a ticket for any other event is refused WITHOUT redeeming
-- it. The organizer-wide checks alone let a ticket for another of the same
-- organizer's events through on the same day. Rewritten, as V23 did, because
-- the CHECK is what keeps an outcome no report groups by out of the table.
ALTER TABLE scan_attempts DROP CONSTRAINT IF EXISTS chk_outcome;

ALTER TABLE scan_attempts ADD CONSTRAINT chk_outcome CHECK (outcome IN (
    'ALLOWED','ALREADY_REDEEMED','WRONG_ORGANIZER',
    'NOT_ASSIGNED_TO_EVENT','TICKET_NOT_FOUND','BOOKING_NOT_CONFIRMED',
    'WRONG_EVENT_DAY','WRONG_EVENT'
));
