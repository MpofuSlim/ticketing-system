-- Local money-movement ledger for payment-service. Every call to
-- POST /payments/deposit or POST /payments/withdraw writes exactly one
-- row: a PENDING insert before payment-service calls Oradian middleware,
-- then an UPDATE to SUCCEEDED or FAILED once the upstream returns. The
-- two writes happen in separate transactions (Propagation.REQUIRES_NEW
-- on TransactionService methods) so an Oradian success followed by a
-- local DB blip leaves a PENDING row in the ledger for reconciliation
-- to investigate rather than the transaction vanishing silently — the
-- same orphan-in-upstream class of bug the tier-2 customer-create flow
-- already fixed via stable idempotency keys.
--
-- Read patterns this schema supports:
--   * Customer history list:    (customer_phone, created_at DESC)
--   * Reconciliation sweep:     (status, created_at) filtered to PENDING
--   * Lookup by Oradian txnID:  (oradian_transaction_id) for support tickets
--
-- Columns kept generous for both transaction types in one table so the
-- ledger query stays a single index-scan rather than UNION across two
-- tables; transaction_type discriminates DEPOSIT vs WITHDRAWAL.

CREATE TABLE transactions (
    id                       UUID         PRIMARY KEY,
    transaction_type         VARCHAR(32)  NOT NULL,
    customer_phone           VARCHAR(32)  NOT NULL,
    source_account_id        VARCHAR(64)  NOT NULL,
    destination_account_id   VARCHAR(64),
    amount                   NUMERIC(19,4) NOT NULL,
    currency                 VARCHAR(8),
    payment_method_name      VARCHAR(64),
    notes                    VARCHAR(500),
    transaction_date         DATE         NOT NULL,
    transaction_branch_id    VARCHAR(64),
    status                   VARCHAR(16)  NOT NULL,
    idempotency_key          VARCHAR(128),
    oradian_transaction_id   VARCHAR(64),
    oradian_reference_number VARCHAR(64),
    oradian_command_id       VARCHAR(64),
    failure_code             VARCHAR(64),
    failure_message          VARCHAR(500),
    correlation_id           VARCHAR(64),
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at             TIMESTAMP WITH TIME ZONE,

    CONSTRAINT chk_transactions_status
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_transactions_type
        CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL')),
    CONSTRAINT chk_transactions_amount_positive
        CHECK (amount > 0)
);

-- "My transactions, newest first" — the FE's primary read.
CREATE INDEX idx_transactions_customer_phone_created_at
    ON transactions(customer_phone, created_at DESC);

-- Reconciliation sweep finds rows stuck in PENDING. Full index rather
-- than partial keeps H2 (PostgreSQL-mode in tests) happy — Postgres-side
-- the index is small enough not to matter.
CREATE INDEX idx_transactions_status_created_at
    ON transactions(status, created_at);

-- Support ticket lookup: customer quotes the Oradian transactionID from
-- their receipt, we resolve back to the local row + audit trail.
CREATE INDEX idx_transactions_oradian_transaction_id
    ON transactions(oradian_transaction_id);
