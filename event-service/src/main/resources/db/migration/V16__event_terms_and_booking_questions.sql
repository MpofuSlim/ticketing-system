-- Event terms and conditions, versioned, and the organizer's booking questions.
--
-- events.terms_version: the version a buyer must accept now; NULL = no terms.
-- Every version's text is kept in event_terms_versions, append-only: a booking
-- records the version it accepted (booking-service), and this is where the
-- exact text of that version is read back from, for the buyer and for a
-- dispute. Removing an event's terms sets terms_version to NULL and deletes
-- nothing.
--
-- event_booking_questions: up to 10 questions answered per ticket at
-- checkout. The answers live in booking-service with the question's label
-- and type copied in, so a question renamed or removed here never changes
-- what a past booking recorded. options and category_ids are JSON arrays of
-- strings (CHOICE options; the category ids the question is limited to, empty
-- = every ticket).
--
-- Additive. Adding a nullable column with no default is a catalog change;
-- the lock_timeout makes a busy events table fail this migration fast rather
-- than queue every event request behind it.
SET LOCAL lock_timeout = '10s';

ALTER TABLE events ADD COLUMN terms_version INTEGER;

CREATE TABLE event_terms_versions (
    id          UUID          PRIMARY KEY,
    event_id    UUID          NOT NULL REFERENCES events (event_id),
    version     INTEGER       NOT NULL,
    text        TEXT          NOT NULL,
    created_at  TIMESTAMP     NOT NULL,
    created_by  VARCHAR(255),
    CONSTRAINT uq_event_terms_versions UNIQUE (event_id, version),
    CONSTRAINT chk_event_terms_version_positive CHECK (version >= 1)
);

CREATE TABLE event_booking_questions (
    id            UUID          PRIMARY KEY,
    event_id      UUID          NOT NULL REFERENCES events (event_id),
    position      INTEGER       NOT NULL,
    label         VARCHAR(80)   NOT NULL,
    type          VARCHAR(16)   NOT NULL,
    required      BOOLEAN       NOT NULL DEFAULT FALSE,
    help_text     VARCHAR(200),
    options       TEXT          NOT NULL DEFAULT '[]',
    max_length    INTEGER,
    category_ids  TEXT          NOT NULL DEFAULT '[]',
    created_at    TIMESTAMP     NOT NULL,
    updated_at    TIMESTAMP,
    CONSTRAINT chk_event_booking_question_type
        CHECK (type IN ('TEXT', 'NUMBER', 'DATE', 'CHOICE', 'CHECKBOX')),
    CONSTRAINT chk_event_booking_question_max_length
        CHECK (max_length IS NULL OR (type = 'TEXT' AND max_length BETWEEN 1 AND 500))
);

CREATE INDEX idx_event_booking_questions_event ON event_booking_questions (event_id, position);
