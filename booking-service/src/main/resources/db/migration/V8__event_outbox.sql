-- V8: event_outbox — transactional outbox for BookingDomainEvent → Kafka.
--
-- BookingEventPublisher used to fire kafkaTemplate.send(...) inside an
-- AFTER_COMMIT @TransactionalEventListener. KafkaTemplate.send is async,
-- so a broker outage / partition rebalance / network blip during the
-- send → the send callback log.warn'd and the event was dropped forever.
-- Downstream consumers (loyalty, analytics, notifications) silently
-- diverged from the booking-service source of truth.
--
-- New flow:
--   BookingService.create/confirm/cancel
--     publishes BookingDomainEvent (in-process) inside the @Transactional
--   BookingEventPublisher listener (now BEFORE_COMMIT, in the same tx)
--     serialises the event to JSON + INSERTs into event_outbox
--   booking transaction commits — outbox row + booking row land together
--   OutboxEventDrainer (every 5s, ShedLock-guarded)
--     SELECTs pending rows, sends via KafkaTemplate, marks published
--     on failure: increment attempts, exponential backoff, flip to
--     giving_up after max-attempts (alerted via Micrometer gauge)
--
-- Same shape as V7's loyalty_earn_retry — the two patterns intentionally
-- mirror each other so an operator already comfortable with one finds
-- the other immediately.

CREATE TABLE event_outbox (
    id              UUID            PRIMARY KEY,
    topic           VARCHAR(128)    NOT NULL,
    event_key       VARCHAR(255)    NOT NULL,
    event_class     VARCHAR(255)    NOT NULL,
    payload         TEXT            NOT NULL,
    attempts        INT             NOT NULL DEFAULT 0,
    last_error      VARCHAR(512),
    next_attempt_at TIMESTAMP       NOT NULL DEFAULT NOW(),
    status          VARCHAR(16)     NOT NULL DEFAULT 'pending',
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_event_outbox_status
        CHECK (status IN ('pending', 'published', 'giving_up')),
    CONSTRAINT chk_event_outbox_attempts_nonneg
        CHECK (attempts >= 0)
);

-- Drainer hot query: WHERE status='pending' AND next_attempt_at <= now()
-- ORDER BY next_attempt_at LIMIT N. Partial index keeps the planner
-- on an index-only scan as published rows accumulate (they're no
-- longer touched by the drainer).
CREATE INDEX idx_event_outbox_pending
    ON event_outbox (next_attempt_at)
    WHERE status = 'pending';

-- Operator query: "show me everything we've given up on". Drives the
-- giving_up gauge LoyaltyEarnRetryJob and OutboxEventDrainer both
-- publish for the alerting dashboard.
CREATE INDEX idx_event_outbox_status
    ON event_outbox (status);
