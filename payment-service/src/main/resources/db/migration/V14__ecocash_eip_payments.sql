-- EcoCash Instant Payment (EIP): the ledger's THIRD rail. POST /payments can
-- now collect via an EcoCash wallet PIN-prompt alongside the InnBucks 2D-code
-- and ZimSwitch card rails (spec: docs/api/ecocash-eip.md).
--
--   ecocash_client_correlator  our idempotency key for the charge request AND
--                              the handle every Query keys on (the EIP twin of
--                              code_auth_number / checkout_id). Persisted on
--                              the row BEFORE the upstream call, so a crash
--                              mid-call leaves a resolvable row, never an
--                              unqueryable one. Unique where present: EcoCash
--                              rejects duplicate correlators upstream, and the
--                              webhook looks rows up by this value.
--   ecocash_reference          EcoCash's own transaction reference
--                              (serverReferenceCode / ecocashReference, e.g.
--                              MP251117.0952.T0527795) once known — the handle
--                              a future refund keys on (originalEcocashReference)
--                              and the support/dispute lookup value.
--
-- code_expires_at is REUSED as the local prompt deadline (same semantics as
-- both other rails: "when the open payment instrument lapses locally"), so
-- the existing staleness/workbasket sweeps cover all three rails unchanged.

ALTER TABLE payment ADD COLUMN ecocash_client_correlator VARCHAR(64);
ALTER TABLE payment ADD COLUMN ecocash_reference VARCHAR(64);

-- Same vocabulary discipline as chk_payment_status / chk_payment_order_type:
-- a new rail can only enter the ledger through a deliberate migration.
ALTER TABLE payment DROP CONSTRAINT chk_payment_rail;
ALTER TABLE payment ADD CONSTRAINT chk_payment_rail
    CHECK (payment_rail IN ('INNBUCKS_CODE', 'ZIMSWITCH_CARD', 'ECOCASH'));

-- Webhook lookup ("which row is this notify about?") + support/dispute
-- lookup by EcoCash's reference. Partial — only ECOCASH rows carry them.
CREATE UNIQUE INDEX uq_payment_ecocash_correlator
    ON payment (ecocash_client_correlator)
    WHERE ecocash_client_correlator IS NOT NULL;
CREATE INDEX idx_payment_ecocash_reference
    ON payment (ecocash_reference)
    WHERE ecocash_reference IS NOT NULL;
