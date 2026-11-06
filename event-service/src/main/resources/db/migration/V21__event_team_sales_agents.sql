-- V21: sales agents on the organizer's event team (owner's decisions, 2026-11-06).
--
-- An organizer's team (V14 event_scanners) was gate staff only. A team row can
-- now SCAN, SELL, or both: the person behind the number accepts one invite in
-- the InnBucks app, exactly as a scanner does, and then sells tickets on the
-- organizer's behalf for the events assigned to them in event_sales_assignments.
--
-- can_scan defaults TRUE and can_sell FALSE, so every existing row is the
-- scan-only team member it was: nothing changes for it. The CHECK keeps a row
-- from holding no role at all.
--
-- portal_email / portal_invited_at: the address the organizer sent a Foundry
-- portal invite to (user-service owns the invite, the account and the
-- password; this side only remembers where it went and when). NULL = no
-- portal access offered.
--
-- event_sales_assignments: one row per (team row, event) the person sells for.
-- ticket_cap NULL = no cap; commission_percent is on what the buyer paid,
-- default 0. No overlap rule: that rule is for scanning only.
--
-- Adding NOT NULL columns with constant defaults is metadata only in Postgres
-- (no rewrite); the CHECK scans the small team table once. lock_timeout so a
-- busy moment fails the migration rather than queueing team reads or event
-- writes (the new table's foreign key briefly locks events); the old pod
-- serves on and a retry succeeds.

SET LOCAL lock_timeout = '5s';

ALTER TABLE event_scanners
    ADD COLUMN can_scan          BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN can_sell          BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN portal_email      VARCHAR(254),
    ADD COLUMN portal_invited_at TIMESTAMP,
    ADD CONSTRAINT chk_event_scanners_has_role CHECK (can_scan OR can_sell);

CREATE TABLE event_sales_assignments (
    assignment_id       UUID            PRIMARY KEY,
    scanner_id          UUID            NOT NULL REFERENCES event_scanners (scanner_id) ON DELETE CASCADE,
    event_id            UUID            NOT NULL REFERENCES events (event_id),
    ticket_cap          INTEGER,
    commission_percent  NUMERIC(5, 2)   NOT NULL DEFAULT 0,
    assigned_by         VARCHAR(255),
    assigned_at         TIMESTAMP       NOT NULL,
    updated_at          TIMESTAMP       NOT NULL,
    CONSTRAINT uq_event_sales_assignments UNIQUE (scanner_id, event_id),
    CONSTRAINT chk_event_sales_assignments_cap CHECK (ticket_cap IS NULL OR ticket_cap > 0),
    CONSTRAINT chk_event_sales_assignments_commission
        CHECK (commission_percent >= 0 AND commission_percent <= 100)
);

-- The organizer's view of an event's sellers, and the event-side FK checks.
CREATE INDEX idx_event_sales_assignments_event ON event_sales_assignments (event_id);
