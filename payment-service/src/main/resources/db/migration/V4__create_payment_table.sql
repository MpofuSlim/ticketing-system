-- Ticketing payment ledger for InnBucks/veengu-backed payments.
--
-- Distinct from the existing `transactions` table (which is the
-- customer-initiated wallet transfer/withdrawal ledger): `payment` is the
-- per-booking checkout debit. One row per attempted payment, PENDING on
-- insert (before the call to innbucks-core-gateway) then UPDATEd to
-- SUCCEEDED or FAILED based on veengu's verdict.
--
-- The two writes happen in separate transactions (Propagation.REQUIRES_NEW
-- on PaymentRecordService) so an upstream success followed by a local DB
-- blip leaves a PENDING row for reconciliation to resolve rather than
-- losing the audit trail. Same orphan-in-upstream defence the
-- transactions ledger uses.
--
-- Read patterns this schema supports:
--   * Reconciliation sweep:           (status, created_at) filtered to PENDING
--   * Customer history by booking:    (booking_id)
--   * Support lookup by veengu txnID: (veengu_transaction_id)
--   * Idempotency-key replay guard:   (idempotency_key) UNIQUE

CREATE TABLE payment (
    id                       UUID         PRIMARY KEY,
    payment_reference        VARCHAR(64)  NOT NULL UNIQUE,
    booking_id               UUID         NOT NULL,
    customer_msisdn          VARCHAR(32)  NOT NULL,
    customer_account         VARCHAR(64)  NOT NULL,
    merchant_account         VARCHAR(64)  NOT NULL,
    amount                   NUMERIC(19,4) NOT NULL,
    currency                 VARCHAR(8)   NOT NULL,
    status                   VARCHAR(16)  NOT NULL,
    veengu_transaction_id    VARCHAR(64),
    upstream_error_code      VARCHAR(64),
    upstream_error_message   VARCHAR(500),
    idempotency_key          VARCHAR(128),
    correlation_id           VARCHAR(64),
    confirmation_number      VARCHAR(64),
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at             TIMESTAMP WITH TIME ZONE,

    CONSTRAINT chk_payment_status
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_payment_amount_positive
        CHECK (amount > 0)
);

-- Reconciliation sweep finds rows stuck in PENDING longer than the
-- threshold (default 5 minutes). Full index over partial index so H2
-- (PostgreSQL-mode in tests) is happy.
CREATE INDEX idx_payment_status_created_at
    ON payment(status, created_at);

-- "Was this booking ever paid?" — answers from a single index seek.
CREATE INDEX idx_payment_booking
    ON payment(booking_id);

-- Support ticket lookup: customer quotes the veengu transaction id from
-- their statement, we resolve back to the local row + booking + correlation.
CREATE INDEX idx_payment_veengu_transaction_id
    ON payment(veengu_transaction_id);

-- Final arbiter against double-execution. The IdempotencyFilter's Redis
-- cache catches the common case; this is the safety net for the rare
-- "cache evicted before retry" path. Same shape as
-- uq_transactions_idempotency_key from V3.
CREATE UNIQUE INDEX uq_payment_idempotency_key
    ON payment(idempotency_key)
    WHERE idempotency_key IS NOT NULL;
