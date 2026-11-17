-- When ZimSwitch last reported a DECLINED card on this row's open checkout.
--
-- A decline read before the checkout's ceiling does not close the row: the
-- checkout can still take a payment until then, so the row stays TOKEN_ISSUED
-- and the response stays AWAITING_PAYMENT. Without this column that response
-- was identical to "the shopper came back without paying", so the website and
-- the Super app told a shopper whose card the bank had just refused "If you
-- were charged, we'll confirm it within 30 minutes" (staging, 2026-11-17,
-- 800.100.100 twice).
--
-- Set by PaymentRecordService.noteCardDeclined, read by the payment response
-- (lastAttemptDeclined). NULL on every other row, and on every row written
-- before this migration: no earlier decline is claimed.
--
-- Nullable with no default, so adding it is a catalogue change only: no
-- table rewrite. The previous image ignores the column.

ALTER TABLE payment ADD COLUMN card_declined_at TIMESTAMP WITH TIME ZONE;
