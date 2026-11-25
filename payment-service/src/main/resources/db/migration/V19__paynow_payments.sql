-- Paynow: three new rails beside the InnBucks code, ZimSwitch card and EcoCash
-- (spec: docs/api/paynow.md). Paynow carries only what the other rails do not:
--   PAYNOW_CARD  web checkout on Paynow's page (Visa/Mastercard, ZimSwitch cards)
--   ONEMONEY     express checkout: a OneMoney prompt on the customer's phone
--   OMARI        express checkout: O'mari texts a code, the customer types it in
--
--   paynow_trace         our merchanttrace (32 hex): sent with every initiate
--                        and persisted BEFORE the call, so a payment whose
--                        reply never arrived can be looked up by it. Paynow
--                        requires it unique per merchant.
--   paynow_poll_url      where we POST to read the status (Paynow's pollurl)
--   paynow_browser_url   PAYNOW_CARD: the page the customer pays on
--   paynow_reference     Paynow's own reference for the transaction
--   paynow_otp_url       OMARI: where we POST the code the customer types
--   paynow_otp_reference OMARI: the reference of the texted code (shown)
--   paynow_checked_at    when we last read the status (spacing the checks)
--   paynow_flagged_at    when the payment went to an operator because money
--                        moved after we closed or confirmed it; set once, so
--                        the same case is not raised twice
--
-- code_expires_at is reused as the local deadline, as on every other rail.
-- Every column is nullable with no default: a catalogue change, no rewrite,
-- and the previous image ignores them. Re-creating the rail CHECK reads every
-- row under the table lock (seconds on today's table). The lock_timeout makes
-- a busy table fail this migration fast (the old pod serves on) rather than
-- queue every payment request behind it.
SET LOCAL lock_timeout = '10s';

ALTER TABLE payment ADD COLUMN paynow_trace VARCHAR(32);
ALTER TABLE payment ADD COLUMN paynow_poll_url VARCHAR(512);
ALTER TABLE payment ADD COLUMN paynow_browser_url VARCHAR(512);
ALTER TABLE payment ADD COLUMN paynow_reference VARCHAR(64);
ALTER TABLE payment ADD COLUMN paynow_otp_url VARCHAR(512);
ALTER TABLE payment ADD COLUMN paynow_otp_reference VARCHAR(64);
ALTER TABLE payment ADD COLUMN paynow_checked_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE payment ADD COLUMN paynow_flagged_at TIMESTAMP WITH TIME ZONE;

-- Same vocabulary discipline as every rail before: a new rail enters the
-- ledger only through a migration (LedgerVocabularyMigrationTest).
ALTER TABLE payment DROP CONSTRAINT chk_payment_rail;
ALTER TABLE payment ADD CONSTRAINT chk_payment_rail
    CHECK (payment_rail IN ('INNBUCKS_CODE', 'ZIMSWITCH_CARD', 'ECOCASH', 'PAYNOW_CARD', 'ONEMONEY', 'OMARI'));

CREATE UNIQUE INDEX uq_payment_paynow_trace
    ON payment (paynow_trace)
    WHERE paynow_trace IS NOT NULL;

-- The re-check of recently closed Paynow payments (a card page can outlive our
-- deadline, and Paynow's update about the late payment can be lost) reads only
-- these rows, by when they closed. No existing row matches.
CREATE INDEX idx_payment_paynow_closed
    ON payment (completed_at)
    WHERE status = 'EXPIRED' AND payment_rail IN ('PAYNOW_CARD', 'ONEMONEY', 'OMARI');
