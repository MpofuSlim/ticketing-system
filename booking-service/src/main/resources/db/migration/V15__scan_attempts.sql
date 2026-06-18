-- Per-attempt audit log for ticket scans.
--
-- A row is written for EVERY ticket-scan attempt by TicketScanService.scan(),
-- regardless of outcome — ALLOWED, ALREADY_REDEEMED, WRONG_ORGANIZER,
-- NOT_ASSIGNED_TO_EVENT, TICKET_NOT_FOUND, BOOKING_NOT_CONFIRMED — so the
-- organizer dashboard can answer "who scanned what, when, with what outcome"
-- without inferring it from the redemption columns on booking_items. Those
-- columns are only ever populated on the happy path; every rejected scan
-- (most of them at peak) would otherwise be invisible past the log file.
--
-- We keep these rows forever (no purge): they're the audit trail behind
-- billing disputes ("we said it was scanned, you said it wasn't"), staff
-- performance reviews, and fraud investigations ("why did Tariro generate
-- 300 ALREADY_REDEEMED events in 10 minutes — did a real attendee actually
-- get into the venue?"). Volumes are bounded by the number of physical
-- people at the gate, not by traffic, so retention is cheap.
--
-- Queries this table serves:
--   * GET /scans/me — the scanner's own attempt history, paginated
--     (idx_scan_attempts_scanner_time).
--   * GET /scans/me/stats — outcome counts for the scanner over a window
--     (same index, GROUP BY outcome).
--   * GET /scans/events/{eventId} — every scan attempt for an event, for the
--     organizer's event-day audit (idx_scan_attempts_event_time).
--   * GET /scans/events/{eventId}/stats — outcome breakdown per event.
--   * GET /scans/team-stats — leaderboard of team-member outcomes for the
--     organizer (idx_scan_attempts_organizer_time, GROUP BY user).
--   * Fraud-signals view (deferred follow-up PR): outcome IN
--     ('ALREADY_REDEEMED','TICKET_NOT_FOUND') trend per scanner, plus
--     ticket_number-keyed lookups via idx_scan_attempts_ticket_time.
--
-- Identity fields are denormalised on purpose: a team member can be renamed,
-- soft-disabled, or have their email rotated in user-service later, and the
-- audit row must still show the human + email the row was created with.
-- scanner_*_uuid is the stable cross-service handle; scanner_email / display
-- name are the human-readable surface the dashboard renders.
--
-- client_ip / user_agent / device_id are NOT exposed via the ScanAttemptDTO
-- the FE consumes — they're server-side fingerprinting bits kept for the
-- fraud view, not for the gate-staff dashboard.

CREATE TABLE scan_attempts (
    id                       UUID PRIMARY KEY,
    attempted_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    outcome                  TEXT NOT NULL,
    ticket_number            TEXT NOT NULL,
    booking_item_id          UUID,
    booking_id               UUID,
    event_id                 UUID,
    scanner_user_uuid        UUID,
    scanner_email            TEXT,
    scanner_display_name     TEXT,
    scanner_organizer_uuid   UUID,
    correlation_id           TEXT,
    client_ip                TEXT,
    user_agent               TEXT,
    device_id                TEXT,
    latency_ms               INTEGER,
    country                  TEXT,
    CONSTRAINT chk_outcome CHECK (outcome IN (
        'ALLOWED','ALREADY_REDEEMED','WRONG_ORGANIZER',
        'NOT_ASSIGNED_TO_EVENT','TICKET_NOT_FOUND','BOOKING_NOT_CONFIRMED'
    ))
);

CREATE INDEX idx_scan_attempts_scanner_time   ON scan_attempts (scanner_user_uuid, attempted_at DESC);
CREATE INDEX idx_scan_attempts_event_time     ON scan_attempts (event_id, attempted_at DESC);
CREATE INDEX idx_scan_attempts_organizer_time ON scan_attempts (scanner_organizer_uuid, attempted_at DESC);
CREATE INDEX idx_scan_attempts_outcome_time   ON scan_attempts (outcome, attempted_at DESC);
CREATE INDEX idx_scan_attempts_ticket_time    ON scan_attempts (ticket_number, attempted_at DESC);
