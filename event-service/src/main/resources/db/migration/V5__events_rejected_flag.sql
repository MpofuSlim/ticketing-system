-- Admin moderation flag for events.
--
-- A SUPER_ADMIN can reject an event (PUT /events/{id}/reject), which sets
-- rejected=TRUE and active=FALSE so it (a) disappears from every public
-- bookable listing — /events/active, /events/search, /events/by-country —
-- and (b) drops into the inactive listing (active=FALSE) where an admin can
-- still find it. PUT /events/{id}/approve clears the flag (rejected=FALSE).
-- The activate endpoint refuses to publish a rejected event, so an organizer
-- can never flip a rejected event back to active=TRUE on their own — which
-- keeps the invariant  active=TRUE => rejected=FALSE  and means
-- "inactive = active=FALSE" always contains every rejected event.
--
-- Defaults FALSE so all existing rows (and future organizer-created events)
-- are publishable until an admin intervenes.
ALTER TABLE events ADD COLUMN rejected BOOLEAN NOT NULL DEFAULT FALSE;

-- The /events/by-country listing's partial index covered (deleted=FALSE AND
-- active=TRUE); that predicate now also carries rejected=FALSE. Rebuild the
-- index so it still satisfies the listing query from the index alone.
DROP INDEX IF EXISTS idx_events_country_active;
CREATE INDEX IF NOT EXISTS idx_events_country_active
    ON events (country) WHERE deleted = FALSE AND active = TRUE AND rejected = FALSE;
