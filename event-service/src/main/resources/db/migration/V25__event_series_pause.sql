-- V25: pausing a recurring series (TIC-71).
--
-- Pausing a series stops new dates (the series' own status, V24) and new sales
-- on the dates already made. A date's sales are stopped by moving its sales end
-- to the moment of the pause, the existing sales-window rule (V22): the date
-- stays listed, its ticket holders keep their tickets and the gate still scans
-- them. These two columns remember that the series did it, and the sales end
-- to put back when the series resumes:
--
--   series_paused_at            when the series paused this date's sales; NULL
--                               when it did not.
--   series_paused_sales_end_at  the date's own sales end before the pause; NULL
--                               when it had none (sales ran until the date ended).
--
-- Additive: two nullable columns, NULL on every existing row, so an older image
-- that never reads them is unaffected.

SET LOCAL lock_timeout = '10s';

ALTER TABLE events ADD COLUMN series_paused_at TIMESTAMP;
ALTER TABLE events ADD COLUMN series_paused_sales_end_at TIMESTAMP;
