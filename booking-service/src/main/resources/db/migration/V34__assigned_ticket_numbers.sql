-- Assigned ticket numbers: bib and seat numbers ("Bib T014", "Table 012").
--
-- category_number_series: one row per seat category that numbers its tickets,
-- set by the event's organizer. A paid ticket gets the next number when its
-- booking is CONFIRMED (BookingService.markConfirmed), never when it is only
-- held, so an abandoned checkout uses none up.
--
-- assigned_count is how many numbers the series has handed out. The next one
-- is start_value + assigned_count. It only ever grows: a refunded ticket keeps
-- its number and no other ticket gets it, so a series can have gaps. It moves
-- only through one UPDATE ... RETURNING that claims a block, under the row's
-- lock, which is what makes the series exact on any number of replicas.
--
-- The terms (label, prefix, start, padding) can be changed or the row deleted
-- only while assigned_count = 0, in the same statement as the change, so a
-- series never prints two shapes.
--
-- booking_items.assigned_number / number_label: the ticket's number and what
-- it is called, written once (assigned_number IS NULL guard) and never
-- changed. Not a credential: the gate admits on ticket_number alone.
--
-- uq_booking_items_assigned_number is the backstop: no two tickets of one
-- category can ever print the same number. Building it reads booking_items
-- once under a SHARE lock (writes wait for those seconds); lock_timeout bounds
-- the wait to take that lock.
SET LOCAL lock_timeout = '10s';

CREATE TABLE category_number_series (
    category_id    UUID         PRIMARY KEY,
    event_id       UUID         NOT NULL,
    label          VARCHAR(20)  NOT NULL,
    prefix         VARCHAR(10)  NOT NULL DEFAULT '',
    start_value    INTEGER      NOT NULL,
    padding        SMALLINT     NOT NULL,
    assigned_count INTEGER      NOT NULL DEFAULT 0,
    updated_by     VARCHAR(254),
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    CONSTRAINT chk_number_series_start CHECK (start_value BETWEEN 0 AND 9999999),
    CONSTRAINT chk_number_series_padding CHECK (padding BETWEEN 0 AND 9),
    CONSTRAINT chk_number_series_assigned CHECK (assigned_count >= 0),
    CONSTRAINT chk_number_series_prefix CHECK (prefix ~ '^[A-Za-z0-9-]{0,10}$')
);

CREATE INDEX idx_number_series_event ON category_number_series (event_id);

ALTER TABLE booking_items ADD COLUMN assigned_number VARCHAR(32);
ALTER TABLE booking_items ADD COLUMN number_label VARCHAR(20);

CREATE UNIQUE INDEX uq_booking_items_assigned_number
    ON booking_items (category_id, assigned_number)
    WHERE assigned_number IS NOT NULL;
