-- Initial schema for seat-service.
-- Mirrors the entity-driven schema Hibernate previously generated via
-- ddl-auto=update. Once Flyway has run V1, Hibernate runs in validate mode
-- and any drift between an entity and the schema fails startup.

CREATE TABLE IF NOT EXISTS seat_categories (
    id                UUID            PRIMARY KEY,
    event_id          UUID            NOT NULL,
    name              VARCHAR(255)    NOT NULL,
    description       VARCHAR(255),
    price             NUMERIC(10, 2)  NOT NULL,
    total_seats       INTEGER         NOT NULL,
    available_seats   INTEGER         NOT NULL,
    deleted           BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP,
    updated_at        TIMESTAMP
);

CREATE TABLE IF NOT EXISTS seats (
    id            UUID            PRIMARY KEY,
    category_id   UUID            NOT NULL,
    row_label     VARCHAR(255)    NOT NULL,
    seat_number   INTEGER         NOT NULL,
    status        VARCHAR(255)    NOT NULL,
    version       BIGINT,
    created_at    TIMESTAMP,
    CONSTRAINT fk_seats_category
        FOREIGN KEY (category_id) REFERENCES seat_categories (id),
    CONSTRAINT uk_seats_category_row_seat
        UNIQUE (category_id, row_label, seat_number)
);
