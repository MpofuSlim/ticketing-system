-- Initial schema for booking-service.
-- Mirrors the entity-driven schema Hibernate previously generated via
-- ddl-auto=update. Once Flyway has run V1, Hibernate runs in validate mode
-- and any drift between an entity and the schema fails startup.

CREATE TABLE IF NOT EXISTS bookings (
    id                    UUID            PRIMARY KEY,
    user_email            VARCHAR(255)    NOT NULL,
    phone_number          VARCHAR(255),
    event_id              UUID            NOT NULL,
    confirmation_number   VARCHAR(255)    NOT NULL,
    status                VARCHAR(255)    NOT NULL,
    total_amount          NUMERIC(10, 2)  NOT NULL,
    expires_at            TIMESTAMP,
    created_at            TIMESTAMP,
    updated_at            TIMESTAMP,
    CONSTRAINT uk_bookings_confirmation_number
        UNIQUE (confirmation_number)
);

CREATE TABLE IF NOT EXISTS booking_items (
    id                  UUID            PRIMARY KEY,
    booking_id          UUID            NOT NULL,
    seat_id             UUID            NOT NULL,
    category_id         UUID            NOT NULL,
    row_label           VARCHAR(255)    NOT NULL,
    seat_number         INTEGER         NOT NULL,
    category_name       VARCHAR(255)    NOT NULL,
    price_at_booking    NUMERIC(10, 2)  NOT NULL,
    ticket_number       VARCHAR(255)    NOT NULL,
    CONSTRAINT fk_booking_items_booking
        FOREIGN KEY (booking_id) REFERENCES bookings (id),
    CONSTRAINT uk_booking_items_ticket_number
        UNIQUE (ticket_number)
);
