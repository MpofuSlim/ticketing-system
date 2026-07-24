-- Due-soon dunning stage: organizers currently only hear about an invoice
-- AFTER it flips OVERDUE. due_soon_notified_at marks the one-time "due in a
-- few days" email so the daily sweep never re-sends. Stamped on the attempt
-- (success or failure — best-effort beats retry spam), same discipline as the
-- booking reminder markers.
ALTER TABLE event_invoices
    ADD COLUMN due_soon_notified_at TIMESTAMP;
