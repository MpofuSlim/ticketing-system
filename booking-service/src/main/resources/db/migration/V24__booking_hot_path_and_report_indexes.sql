-- Indexes for the booking hot path and the organizer reports, from the
-- optimization-checklist audit. Each one matches a query that was a
-- sequential scan; checked with EXPLAIN on Postgres 16 against 100k rows.
--
-- Plain CREATE INDEX (not CONCURRENTLY): Flyway runs this in a transaction,
-- and at today's row counts the brief write lock is milliseconds. Revisit
-- with CONCURRENTLY (one statement per migration, non-transactional) once
-- these tables are large.

-- Every POST /bookings counts the category's active items
-- (BookingItemRepository.countActiveByCategoryId) before claiming capacity,
-- and the public availability batch and the by-category guest list filter
-- on it too. booking_items had indexes on booking_id and seat_id only.
CREATE INDEX IF NOT EXISTS idx_booking_items_category_id
    ON booking_items (category_id);

-- Organizer reports (OrganizerReportRepository): status is a JPQL literal and
-- the window is a created_at range, with an optional organizer. The first
-- index serves the platform-wide report, the second an organizer's own.
CREATE INDEX IF NOT EXISTS idx_bookings_status_created_at
    ON bookings (status, created_at);

CREATE INDEX IF NOT EXISTS idx_bookings_tenant_user_created_at
    ON bookings (tenant_user_uuid, created_at);
