-- Money-path backstop: make idempotency_key a durable uniqueness guarantee,
-- not just an app-layer (Redis/in-memory) convention.
--
-- Today idempotency is enforced only by IdempotencyFilter. If the store is
-- down, the key TTL lapses, or the filter is bypassed, two identical
-- POST /payments/transfer (or /withdraw) calls can each INSERT a money row
-- — a double transfer. loyalty-service hit the same TOCTOU on
-- (merchant_id, reference) and fixed it in its V5 with a partial unique
-- index as the final arbiter; this applies the same lesson to the actual
-- money ledger.
--
-- Partial (WHERE idempotency_key IS NOT NULL) so rows written without a
-- client-supplied key don't collide on NULL.
--
-- Follow-up (NOT in this migration, flagged to the team): payment-service
-- should catch the DataIntegrityViolationException this index can raise and
-- surface it as the cached/idempotent response, the way loyalty's
-- TransactionService turns it into a 409. Until then, the rare case where
-- the app-layer store has already failed degrades to a 500 — which is the
-- safe direction (request rejected, no double-money) but not graceful.

CREATE UNIQUE INDEX IF NOT EXISTS uq_transactions_idempotency_key
    ON transactions (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
