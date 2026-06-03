-- V11: partial index supporting the periodic purge of published outbox rows.
--
-- OutboxEventPurgeJob (new in this commit) deletes 'published' rows older
-- than app.event-outbox.purge.retention every hour. Without this index
-- the planner falls back to a Seq Scan once published rows accumulate
-- millions deep — and every booking creates 1-3 events, so the table
-- hits O(100M) rows fast at projected throughput. The existing
-- idx_event_outbox_pending only helps the drainer (status='pending'),
-- and idx_event_outbox_status is a non-partial general-purpose index
-- that the planner often skips in favour of seqscan when published
-- rows are the vast majority.
--
-- Partial form keeps the index small (only the rows actually eligible
-- for purge) and lets the purge SELECT stay on an index scan + heap
-- fetch of the LIMIT slice.

CREATE INDEX idx_event_outbox_published_updated_at
    ON event_outbox (updated_at)
    WHERE status = 'published';
