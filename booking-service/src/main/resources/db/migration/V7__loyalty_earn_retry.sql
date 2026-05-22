-- V7: loyalty-earn retry table + shedlock infrastructure.
--
-- BookingService.applyLoyalty used to swallow loyalty.earn failures with
-- only a log.warn — the customer paid for points they never received and
-- nobody noticed. New flow:
--
--   confirmBooking -> applyLoyalty.earn(...) throws
--                  -> insert loyalty_earn_retry row
--                  -> HTTP 200 OK to customer (booking still succeeds)
--
--   LoyaltyEarnRetryJob (every 60s, ShedLock-guarded):
--                  -> drain pending rows with exponential backoff
--                  -> on success, mark succeeded
--                  -> on repeated failure (>= max_attempts), mark
--                     giving_up and alert via Micrometer counter
--
-- The redeem leg stays in-band: if a customer asks to redeem points and
-- loyalty-service is down, we MUST fail the booking confirm rather than
-- let them buy something without burning the points they said they'd use.
-- Only the earn leg is "best effort + queue for retry".


-- 1. ShedLock table — provisions the leader-election storage that
--    SchedulerLockConfig's JdbcTemplateLockProvider reads. Without this,
--    multi-replica deploys would run LoyaltyEarnRetryJob (and
--    BookingExpirationService) on every pod simultaneously; for the
--    retry job that means duplicate loyalty.earn calls for the same row,
--    inverting the very guarantee the retry table is supposed to provide.
CREATE TABLE IF NOT EXISTS shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMP    NOT NULL,
    locked_at   TIMESTAMP    NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);


-- 2. loyalty_earn_retry — one row per failed earn attempt. Eventually
--    transitions to succeeded (loyalty.earn returned 2xx on a retry) or
--    giving_up (max_attempts exhausted, raise an alert).
CREATE TABLE loyalty_earn_retry (
    id              UUID            PRIMARY KEY,
    booking_id      UUID            NOT NULL,
    customer_email  VARCHAR(255)    NOT NULL,
    tenant_id       VARCHAR(64)     NOT NULL,
    cash_amount     NUMERIC(10, 2)  NOT NULL,
    reference       VARCHAR(64)     NOT NULL,
    attempts        INT             NOT NULL DEFAULT 0,
    last_error      VARCHAR(512),
    next_attempt_at TIMESTAMP       NOT NULL DEFAULT NOW(),
    status          VARCHAR(16)     NOT NULL DEFAULT 'pending',
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_loyalty_earn_retry_status
        CHECK (status IN ('pending', 'succeeded', 'giving_up')),
    CONSTRAINT chk_loyalty_earn_retry_cash_nonneg
        CHECK (cash_amount >= 0),
    CONSTRAINT chk_loyalty_earn_retry_attempts_nonneg
        CHECK (attempts >= 0)
);

-- Drainer query: WHERE status='pending' AND next_attempt_at <= NOW()
-- ORDER BY next_attempt_at LIMIT N. Index supports the predicate + the sort
-- without touching the heap for the LIMIT slice.
CREATE INDEX idx_loyalty_earn_retry_pending
    ON loyalty_earn_retry (next_attempt_at)
    WHERE status = 'pending';

-- Operator query: "show me everything we've given up on" + dashboard panel
-- for the alerting threshold. Cheap unfiltered index on a low-cardinality
-- column is fine because the table is bounded by traffic volume × retry
-- TTL, not by population size.
CREATE INDEX idx_loyalty_earn_retry_status
    ON loyalty_earn_retry (status);

-- Forensic / reconciliation query: "show me every retry attempted for
-- booking X". One booking can spawn at most one retry row today, but
-- indexed in case the contract ever loosens.
CREATE INDEX idx_loyalty_earn_retry_booking
    ON loyalty_earn_retry (booking_id);
