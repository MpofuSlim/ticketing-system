-- Trigram indexes for the public event search (EventRepository.searchByKeyword)
-- and every venue filter. They match LOWER(col) LIKE '%q%', which no B-tree
-- can serve, so each search was a sequential scan of events. With all three
-- in place Postgres ORs them in one bitmap scan (EXPLAIN-checked on
-- Postgres 16, 100k rows). Queries shorter than three characters still scan:
-- a trigram index has nothing to match on.
--
-- The index expressions must stay exactly LOWER(title) / LOWER(description) /
-- LOWER(venue): that is what Hibernate renders for LOWER(e.x) in the queries,
-- and an index on any other expression is never used.
--
-- pg_trgm ships with the postgres:16 image and is a trusted extension, so the
-- database owner can create it without superuser.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_events_title_trgm
    ON events USING gin (LOWER(title) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_events_venue_trgm
    ON events USING gin (LOWER(venue) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_events_description_trgm
    ON events USING gin (LOWER(description) gin_trgm_ops);
