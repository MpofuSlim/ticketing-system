-- Adds geographic coordinates and a banner image to events.
-- Latitude/longitude are stored as embedded fields on Event (Location).
-- The banner is stored inline as binary data alongside its content type.

ALTER TABLE events
    ADD COLUMN latitude                  DOUBLE PRECISION,
    ADD COLUMN longitude                 DOUBLE PRECISION,
    ADD COLUMN event_banner              BYTEA,
    ADD COLUMN event_banner_content_type VARCHAR(100);

ALTER TABLE events
    ADD CONSTRAINT chk_events_latitude_range
        CHECK (latitude IS NULL OR (latitude >= -90 AND latitude <= 90)),
    ADD CONSTRAINT chk_events_longitude_range
        CHECK (longitude IS NULL OR (longitude >= -180 AND longitude <= 180));
