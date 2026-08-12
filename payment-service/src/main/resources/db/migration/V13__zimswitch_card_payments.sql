-- ZimSwitch Online (COPYandPAY) card payments: the ledger's SECOND rail.
-- POST /payments can now collect by card via the ZimSwitch ecommerce gateway
-- alongside the InnBucks 2D-code rail (spec: docs/api/zimswitch-copyandpay.md).
--
--   payment_rail            which collection rail the row runs on. Every
--                           pre-V13 row is an InnBucks 2D-code payment.
--   checkout_id             COPYandPAY checkout handle ("id" from
--                           POST /v1/checkouts) — the card twin of
--                           code_auth_number: the widget loads with it and
--                           every status query keys on it. Stays reusable
--                           upstream until a payment finalizes, hard ceiling
--                           30 minutes.
--   checkout_integrity      SRI digest for the widget <script> tag (returned
--                           when integrity=true is sent) — pins the
--                           third-party script that handles card entry.
--   card_brand              brand the shopper actually paid with (from the
--                           status response's echo verification).
--   card_status_checked_at  when the status endpoint was last queried for
--                           this row. The gateway throttles status reads to
--                           TWO per checkout per minute; poller + instant
--                           check both gate on this stamp to stay inside it.
--
-- code_expires_at is REUSED as the card checkout's local deadline (same
-- semantics: "when the open payment instrument lapses locally"), so the
-- existing staleness/workbasket sweeps cover both rails unchanged.

ALTER TABLE payment ADD COLUMN payment_rail VARCHAR(16) NOT NULL DEFAULT 'INNBUCKS_CODE';
ALTER TABLE payment ADD COLUMN checkout_id VARCHAR(64);
ALTER TABLE payment ADD COLUMN checkout_integrity VARCHAR(128);
ALTER TABLE payment ADD COLUMN card_brand VARCHAR(32);
ALTER TABLE payment ADD COLUMN card_status_checked_at TIMESTAMP WITH TIME ZONE;

-- Same vocabulary discipline as chk_payment_status / chk_payment_order_type:
-- a new rail can only enter the ledger through a deliberate migration.
ALTER TABLE payment ADD CONSTRAINT chk_payment_rail
    CHECK (payment_rail IN ('INNBUCKS_CODE', 'ZIMSWITCH_CARD'));

-- Support-ticket / dispute lookup: "which row is this gateway checkout?".
-- Partial — only card rows carry one.
CREATE INDEX idx_payment_checkout_id
    ON payment (checkout_id)
    WHERE checkout_id IS NOT NULL;
