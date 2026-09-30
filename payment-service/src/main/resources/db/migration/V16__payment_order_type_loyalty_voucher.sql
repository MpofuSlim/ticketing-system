-- LOYALTY_VOUCHER joins the order-type vocabulary.
--
-- The LOYALTY_VOUCHER order type (InnRewards V47 voucher purchase orders,
-- VCH-... refs) shipped in code without this migration, so chk_payment_order_type
-- (V12: BOOKING, MARKETPLACE) refused EVERY voucher payment row on all three
-- rails. openPending's DataIntegrityViolationException was then read as the
-- uq_payment_active_order race and answered 409 "already in progress" — no
-- row, no prompt, no charge, and a message that pointed everyone the wrong way.
--
-- Same vocabulary discipline as chk_payment_rail: a new order type enters the
-- ledger only through a deliberate migration. OrderTypeVocabularyTest now
-- fails the build when an enum value has no matching CHECK entry.
--
-- Postgres re-validates existing rows when the constraint is re-added; every
-- existing row is BOOKING or MARKETPLACE, both still allowed.

ALTER TABLE payment DROP CONSTRAINT chk_payment_order_type;
ALTER TABLE payment ADD CONSTRAINT chk_payment_order_type
    CHECK (order_type IN ('BOOKING', 'MARKETPLACE', 'LOYALTY_VOUCHER'));
