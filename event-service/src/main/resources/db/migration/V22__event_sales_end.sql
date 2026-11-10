-- Ticket sales can close at a scheduled time, before the event ends.
--
-- sales_end_at is UTC like sales_start_at (V15), converted from the
-- market-local time the organizer typed (MarketTimeZone.toUtc). NULL means
-- sales close when the event ends, which is exactly what every existing event
-- did, so no backfill is needed. Nothing runs when the time arrives: every read
-- works out the sales status at that moment (SalesStatus.of: SALES_CLOSED), and
-- booking-service asks for it live before every sale and every hold extension
-- (GET /events/internal/sales-window).
--
-- event_sales_window_changes is the record of who set or moved the sales
-- window, and when. One row when an event is created with a sales start or end,
-- and one each time an update changes either. Both sides are stored as they
-- were (UTC wall clock, no zone), NULL meaning "no schedule". changed_by is the
-- JPA auditor (organizer uuid or the JWT email), as on V9.
--
-- event_id has NO foreign key on purpose, like seat-service's price history
-- (V11): the record outlives the event row, and a hard delete must not be
-- blocked by its own history. The trigger refuses UPDATE and DELETE, so no code
-- path can rewrite who moved a sales window, or when.
--
-- The ADD COLUMN is nullable with no default: a catalog-only change (no table
-- rewrite). lock_timeout bounds its brief ACCESS EXCLUSIVE lock, so a
-- long-running reader makes the migration fail fast rather than queue every
-- event request behind it.
SET LOCAL lock_timeout = '10s';

ALTER TABLE events ADD COLUMN sales_end_at TIMESTAMP;

CREATE TABLE event_sales_window_changes (
    id                    UUID PRIMARY KEY,
    event_id              UUID         NOT NULL,
    change_type           VARCHAR(20)  NOT NULL,
    old_sales_start_at    TIMESTAMP,
    new_sales_start_at    TIMESTAMP,
    old_sales_end_at      TIMESTAMP,
    new_sales_end_at      TIMESTAMP,
    changed_by            VARCHAR(255),
    changed_at            TIMESTAMP    NOT NULL,
    CONSTRAINT chk_sales_window_change_type CHECK (change_type IN ('CREATED', 'CHANGED'))
);

CREATE INDEX idx_sales_window_changes_event_at ON event_sales_window_changes (event_id, changed_at);

CREATE FUNCTION event_sales_window_changes_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'event_sales_window_changes is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER event_sales_window_changes_append_only
    BEFORE UPDATE OR DELETE ON event_sales_window_changes
    FOR EACH ROW EXECUTE FUNCTION event_sales_window_changes_append_only();
