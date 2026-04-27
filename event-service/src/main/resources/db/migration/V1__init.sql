-- Initial schema for event-service.
-- Mirrors the entity-driven schema Hibernate previously generated via
-- ddl-auto=update. Once Flyway has run V1, Hibernate runs in validate mode
-- and any drift between an entity and the schema fails startup.

CREATE TABLE IF NOT EXISTS events (
    event_id            UUID            PRIMARY KEY,
    tenant_id           VARCHAR(255)    NOT NULL,
    title               VARCHAR(255)    NOT NULL,
    description         TEXT,
    venue               VARCHAR(255)    NOT NULL,
    province            VARCHAR(3)      NOT NULL,
    date_time           TIMESTAMP       NOT NULL,
    total_capacity      INTEGER         NOT NULL,
    available_tickets   INTEGER         NOT NULL,
    version             BIGINT,
    deleted             BOOLEAN         NOT NULL DEFAULT FALSE,
    active              BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP,
    CONSTRAINT uk_events_natural_key
        UNIQUE (tenant_id, title, venue, date_time)
);
