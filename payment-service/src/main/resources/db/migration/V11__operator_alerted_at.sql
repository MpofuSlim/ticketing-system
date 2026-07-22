-- One-time stuck-payment alert marker: when the reconciler's confirm retry
-- keeps failing for a COMPLETED_UNCONFIRMED payment, UnconfirmedPaymentAlerter
-- sends the operator email + customer WhatsApp ONCE and stamps this column so
-- the nightly sweep never re-alerts the same stuck row.
ALTER TABLE payment ADD COLUMN operator_alerted_at TIMESTAMP WITH TIME ZONE;
