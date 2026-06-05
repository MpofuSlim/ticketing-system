-- Indexed random sampling for GET /seats/available?limit=N.
--
-- Booking-service samples a few random AVAILABLE seats per booking via a random
-- UUID pivot:
--   WHERE category_id = ? AND status = 'AVAILABLE' AND id >= :pivot
--   ORDER BY id LIMIT n            (wrapping to the smallest ids if short)
--
-- The existing idx_seats_category_status (category_id, status) seeks to the
-- AVAILABLE rows but carries no id ordering, so the database had to read the
-- whole available pool and sort it to satisfy ORDER BY id / ORDER BY random().
-- That is O(N) in the category's seat count: on a six-figure category it ran
-- ~1s under load, saturating seat-service and tripping booking-service's 1s
-- circuit breaker. Adding `id` as the trailing index column lets the query seek
-- straight to the pivot and walk forward in id order, returning the first n
-- rows: O(log N + n), independent of how large the category is.
CREATE INDEX IF NOT EXISTS idx_seats_category_status_id
    ON seats (category_id, status, id);
