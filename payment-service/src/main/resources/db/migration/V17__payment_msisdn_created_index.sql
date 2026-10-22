-- The EcoCash per-number prompt cap counts a number's recent charges on every
-- POST /payments that starts one (EcocashPaymentService.startCharge):
--
--   WHERE payment_rail = 'ECOCASH' AND customer_msisdn IN (...) AND created_at > ?
--
-- No index led with customer_msisdn, so that count read the whole table.
-- Plain, not partial on the rail: the rail is a bind parameter, which a
-- generic plan cannot match to a partial index's predicate.
--
-- The payment table is small on every cell, so a plain CREATE INDEX (which
-- blocks writes to it for the moment it takes) is fine inside Flyway's
-- transaction.

CREATE INDEX IF NOT EXISTS idx_payment_msisdn_created
    ON payment (customer_msisdn, created_at);
