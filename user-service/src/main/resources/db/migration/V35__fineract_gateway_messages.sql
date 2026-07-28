-- Store-and-forward queue for the Fineract Message Gateway facade
-- (POST /fineract-gateway/sms + /fineract-gateway/sms/report). Fineract
-- submits SMS batches keyed by ITS OWN message id (fineract_id) and later
-- polls for delivery reports by those same ids, so rows must be addressable
-- by (tenant_id, fineract_id) and that pair must be unique — a Fineract
-- retry of an already-accepted batch must dedupe, not double-send (the
-- rail carries OTPs and campaign texts).
--
-- delivery_status uses FINERACT's own code space (SmsMessageStatusType) so
-- the report endpoint echoes codes without translation:
--   0 = INVALID, 100 = PENDING, 200 = SENT, 300 = DELIVERED, 400 = FAILED.
--
-- message is nullable ON PURPOSE: the body can carry an OTP (A02 — OTPs are
-- never stored plaintext at rest elsewhere in this service), so the
-- dispatcher nulls it out the moment the row reaches a terminal status.
-- Plaintext exists only while the row is queued for its send attempt.
CREATE TABLE fineract_gateway_messages (
    id             BIGSERIAL PRIMARY KEY,
    fineract_id    BIGINT       NOT NULL,
    tenant_id      VARCHAR(100) NOT NULL,
    mobile_number  VARCHAR(32)  NOT NULL,
    message        TEXT,
    provider_id    BIGINT,
    channel        VARCHAR(16)  NOT NULL,
    delivery_status SMALLINT    NOT NULL DEFAULT 100,
    error_message  VARCHAR(500),
    external_ref   VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    sent_at        TIMESTAMP,
    CONSTRAINT uq_fineract_gateway_tenant_msg UNIQUE (tenant_id, fineract_id)
);

-- The dispatcher re-queues stuck PENDING rows and the report endpoint
-- filters by tenant + id list; both ride the unique index above. This one
-- serves the "find undispatched work" sweep.
CREATE INDEX idx_fineract_gateway_status
    ON fineract_gateway_messages (delivery_status)
    WHERE delivery_status = 100;
