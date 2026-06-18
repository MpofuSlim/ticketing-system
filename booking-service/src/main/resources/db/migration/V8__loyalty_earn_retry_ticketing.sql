-- V8: ticketing loyalty integration — retry rows carry organizer_uuid + phone.
--
-- The earn call now targets loyalty-service's S2S ticketing endpoint keyed by
-- organizer (= merchant) + phone, so the retry row needs those fields. The old
-- customer_email / tenant_id columns are superseded for new rows; relax their
-- NOT NULL so inserts don't have to carry the legacy identity.

ALTER TABLE loyalty_earn_retry ADD COLUMN organizer_uuid UUID;
ALTER TABLE loyalty_earn_retry ADD COLUMN phone_number   VARCHAR(32);

ALTER TABLE loyalty_earn_retry ALTER COLUMN customer_email DROP NOT NULL;
ALTER TABLE loyalty_earn_retry ALTER COLUMN tenant_id      DROP NOT NULL;
