-- Allow the WRONG_EVENT_DAY scan outcome.
--
-- A ticket now scans only on the market-local calendar days its event spans;
-- a scan outside that window is refused WITHOUT redeeming the ticket, and the
-- attempt is audited like every other outcome. scan_attempts.outcome is a
-- TEXT column guarded by a CHECK rather than a native enum (V15), so the
-- constraint has to be rewritten to admit the new value — otherwise the very
-- first off-day scan fails the insert and the whole scan transaction rolls
-- back, turning an intended refusal into a 500.
--
-- Rewritten rather than dropped: the CHECK is what keeps a typo in application
-- code from silently persisting an outcome no report groups by.
ALTER TABLE scan_attempts DROP CONSTRAINT IF EXISTS chk_outcome;

ALTER TABLE scan_attempts ADD CONSTRAINT chk_outcome CHECK (outcome IN (
    'ALLOWED','ALREADY_REDEEMED','WRONG_ORGANIZER',
    'NOT_ASSIGNED_TO_EVENT','TICKET_NOT_FOUND','BOOKING_NOT_CONFIRMED',
    'WRONG_EVENT_DAY'
));
