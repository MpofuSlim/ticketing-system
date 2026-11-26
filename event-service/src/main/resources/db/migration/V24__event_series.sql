-- V24: recurring event series (TIC-70).
--
-- A series is a schedule plus a TEMPLATE event. The organizer sets the
-- template up like any event (ticket types, tiers, add-ons, questions, terms,
-- banner, gate team) through the existing endpoints; the generator copies it
-- onto each date the schedule makes inside the booking window. Each date is an
-- ordinary event row carrying series_id, so every existing read, booking, scan
-- and report works on it unchanged.
--
-- Additive: one new table, two nullable columns on events, three CHECKs and two
-- partial indexes. Every existing row has series_id and series_state NULL,
-- which every CHECK allows, so nothing an older image writes is refused.

SET LOCAL lock_timeout = '10s';

CREATE TABLE event_series (
    series_id                  UUID         PRIMARY KEY,
    template_event_id          UUID         NOT NULL UNIQUE REFERENCES events (event_id),
    tenant_user_uuid           UUID         NOT NULL,
    status                     VARCHAR(16)  NOT NULL,
    -- JSON arrays of strings (JsonStringListConverter): ["FRIDAY","SATURDAY"],
    -- ["19:00","21:30"], ["2026-12-25"], ["2026-12-24T15:00"]. Market-local.
    days_of_week               TEXT         NOT NULL,
    start_times                TEXT         NOT NULL,
    skip_dates                 TEXT         NOT NULL DEFAULT '[]',
    extra_starts               TEXT         NOT NULL DEFAULT '[]',
    duration_minutes           INTEGER      NOT NULL,
    start_date                 DATE         NOT NULL,
    end_date                   DATE,
    booking_window_days        INTEGER      NOT NULL DEFAULT 60,
    sales_open_minutes_before  INTEGER,
    sales_close_minutes_before INTEGER,
    version                    BIGINT       NOT NULL DEFAULT 0,
    created_at                 TIMESTAMP    NOT NULL,
    updated_at                 TIMESTAMP,
    created_by                 VARCHAR(255),
    updated_by                 VARCHAR(255),
    CONSTRAINT chk_event_series_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'ENDED')),
    CONSTRAINT chk_event_series_duration
        CHECK (duration_minutes BETWEEN 1 AND 1440),
    CONSTRAINT chk_event_series_booking_window
        CHECK (booking_window_days BETWEEN 1 AND 180),
    CONSTRAINT chk_event_series_dates
        CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT chk_event_series_sales_window
        CHECK ((sales_open_minutes_before IS NULL OR sales_open_minutes_before >= 1)
           AND (sales_close_minutes_before IS NULL OR sales_close_minutes_before >= 0)
           AND (sales_open_minutes_before IS NULL OR sales_close_minutes_before IS NULL
                OR sales_open_minutes_before > sales_close_minutes_before))
);

-- The generator's list of series to work on.
CREATE INDEX idx_event_series_status ON event_series (status);
-- An organizer's own series.
CREATE INDEX idx_event_series_owner ON event_series (tenant_user_uuid);

ALTER TABLE events ADD COLUMN series_id UUID REFERENCES event_series (series_id);
ALTER TABLE events ADD COLUMN series_state VARCHAR(16);

ALTER TABLE events ADD CONSTRAINT chk_events_series_state
    CHECK (series_state IS NULL OR series_state IN ('TEMPLATE', 'GENERATING', 'READY'));
ALTER TABLE events ADD CONSTRAINT chk_events_series_pair
    CHECK ((series_id IS NULL) = (series_state IS NULL));
-- A template is never published, and neither is a date still being made: the
-- database refuses it whatever the code does.
ALTER TABLE events ADD CONSTRAINT chk_events_series_unpublished
    CHECK (series_state IS NULL OR series_state = 'READY' OR active = FALSE);

-- One date per start per series, deleted dates included: a date the organizer
-- deleted is never made again. The template is outside it.
CREATE UNIQUE INDEX uq_events_series_start ON events (series_id, start_date_time)
    WHERE series_state IN ('GENERATING', 'READY');
-- A series' dates.
CREATE INDEX idx_events_series ON events (series_id) WHERE series_id IS NOT NULL;
