-- 2D-code payments (InnBucks Merchant API): POST /payments now issues an
-- InnBucks PAYMENT code the customer approves in their own app/USSD, instead
-- of debiting the wallet server-side. The ledger gains the code handles and
-- the TOKEN_ISSUED waiting state (already present in the V5 CHECK constraint
-- and the uq_payment_active_booking index — both were declared code-flow-ready).

-- 1) Direct-debit-era columns lose their NOT NULL: the code flow has no
--    wallet lookup (customer pays from their own app) and no destination
--    account (the merchant identity is implicit in the API credentials).
--    Historical rows keep their values.
ALTER TABLE payment ALTER COLUMN customer_account DROP NOT NULL;
ALTER TABLE payment ALTER COLUMN merchant_account DROP NOT NULL;

-- 2) Code handles.
--    innbucks_code     the code the customer pays ("code" from /api/code/generate)
--    code_auth_number  InnBucks-side handle ("authNumber") — the
--                      originalReference every status query keys on
--    code_expires_at   local deadline (issue time + configured TTL); the
--                      poller expires still-New codes shortly after it passes
ALTER TABLE payment ADD COLUMN innbucks_code VARCHAR(32);
ALTER TABLE payment ADD COLUMN code_auth_number VARCHAR(64);
ALTER TABLE payment ADD COLUMN code_expires_at TIMESTAMP WITH TIME ZONE;

-- 3) Poller scan support: the code-status poll reads TOKEN_ISSUED rows every
--    ~20s. Partial index keeps it O(open codes), not O(all payments).
CREATE INDEX idx_payment_token_issued
    ON payment (created_at)
    WHERE status = 'TOKEN_ISSUED';
