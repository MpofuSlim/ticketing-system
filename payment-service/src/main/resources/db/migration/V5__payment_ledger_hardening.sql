-- Payment-ledger hardening: banking-grade state machine, append-only
-- transition journal, and a DB-enforced one-active-payment-per-booking
-- invariant. Groundwork for replacing the s2s gateway debit with the
-- direct veengu Purchase flow (paymentDetails -> consent -> purchase).
--
-- 1) Status vocabulary. Two states are wired by code in this change:
--      IN_DOUBT              an upstream call timed out / returned an
--                            unclassifiable outcome — money MAY have moved;
--                            never auto-failed, only reconciled by querying.
--      COMPLETED_UNCONFIRMED money definitely moved (debit COMPLETED) but
--                            the booking confirm failed — the one state a
--                            human or the reconciler MUST resolve; was
--                            previously (mis)recorded as FAILED.
--    The remaining new values are RESERVED for the direct veengu Purchase
--    flow (TOKEN_ISSUED, CONSENTED, EXECUTING, REQUIRES_AUTH, REJECTED,
--    EXPIRED) — declared now so the constraint doesn't need another
--    migration when that adapter lands; no code writes them yet.
ALTER TABLE payment ALTER COLUMN status TYPE VARCHAR(32);
ALTER TABLE payment DROP CONSTRAINT chk_payment_status;
ALTER TABLE payment ADD CONSTRAINT chk_payment_status CHECK (status IN (
    'PENDING', 'SUCCEEDED', 'FAILED',
    'IN_DOUBT', 'COMPLETED_UNCONFIRMED',
    'TOKEN_ISSUED', 'CONSENTED', 'EXECUTING', 'REQUIRES_AUTH',
    'REJECTED', 'EXPIRED'
));

-- 2) Append-only transition journal. One row per state transition (the
--    opening insert journals NULL -> PENDING). Written in the SAME
--    transaction as the status change (PaymentRecordService) so ledger and
--    journal cannot diverge. Rows are never updated or deleted — this is
--    the audit trail reconstruction + dispute handling read from.
CREATE TABLE payment_event (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id     UUID NOT NULL REFERENCES payment(id),
    from_status    VARCHAR(32),
    to_status      VARCHAR(32) NOT NULL,
    detail         VARCHAR(500),
    upstream_ref   VARCHAR(64),
    correlation_id VARCHAR(64),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payment_event_payment ON payment_event(payment_id, created_at);

-- 3) One active-or-successful payment per booking, enforced by the
--    DATABASE (application-level checks lie under concurrency; indexes
--    don't). Terminal failures (FAILED / REJECTED / EXPIRED) free the slot
--    so a customer can retry after a decline; anything else blocks a
--    second row.
--
--    Pre-clean: any historical duplicates (two non-terminal-failed rows
--    for one booking, possible before this index existed) are closed as
--    superseded — keeping the SUCCEEDED row if one exists, else the most
--    recent. Each mutated row gets a journal entry so the cleanup itself
--    is auditable.
INSERT INTO payment_event (payment_id, from_status, to_status, detail)
SELECT p.id, p.status, 'FAILED',
       'V5 ledger-hardening migration: closed as superseded duplicate — another active/successful payment exists for booking ' || p.booking_id
FROM payment p
WHERE p.id IN (
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (
            PARTITION BY booking_id
            ORDER BY CASE WHEN status = 'SUCCEEDED' THEN 0 ELSE 1 END,
                     created_at DESC, id
        ) AS rn
        FROM payment
        WHERE status NOT IN ('FAILED', 'REJECTED', 'EXPIRED')
    ) ranked WHERE rn > 1
);

UPDATE payment
SET status = 'FAILED',
    upstream_error_code = 'superseded_duplicate',
    upstream_error_message = 'Closed by V5 ledger-hardening migration: another active/successful payment exists for this booking',
    completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP)
WHERE id IN (
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (
            PARTITION BY booking_id
            ORDER BY CASE WHEN status = 'SUCCEEDED' THEN 0 ELSE 1 END,
                     created_at DESC, id
        ) AS rn
        FROM payment
        WHERE status NOT IN ('FAILED', 'REJECTED', 'EXPIRED')
    ) ranked WHERE rn > 1
);

CREATE UNIQUE INDEX uq_payment_active_booking
    ON payment(booking_id)
    WHERE status NOT IN ('FAILED', 'REJECTED', 'EXPIRED');
