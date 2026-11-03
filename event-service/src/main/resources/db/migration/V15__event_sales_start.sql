-- Ticket sales can open at a scheduled time, separately from the event's start.
--
-- sales_start_at is UTC like every other timestamp here, converted from the
-- market-local time the organizer typed (MarketTimeZone.toUtc). NULL means
-- tickets are on sale as soon as the event is published, which is exactly what
-- every existing event did, so no backfill is needed.
--
-- Nothing runs when the time arrives: every read works out the sales status at
-- that moment (SalesStatus.of), and booking-service asks for it live before
-- every sale (GET /events/internal/sales-window).
--
-- A nullable column with no default is a catalog-only change in Postgres (no
-- table rewrite). lock_timeout still bounds the brief ACCESS EXCLUSIVE lock, so
-- a long-running reader makes the migration fail fast rather than queue every
-- event request behind it.
SET LOCAL lock_timeout = '10s';

ALTER TABLE events ADD COLUMN sales_start_at TIMESTAMP;
