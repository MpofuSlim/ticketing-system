-- Opt-in "the price goes up soon" reminders (V28). A customer taps "Remind me"
-- on an event; about a day before the price tier on sale in one of its
-- categories ends, booking-service's hourly job sends them ONE message
-- (WhatsApp, SMS fallback) naming the tier, its price and when it ends.
--
-- event_reminder_subscriptions: one row per (event, phone), the phone taken
-- from the customer's own session, never from a request body.
-- unsubscribed_at set = off; subscribing again clears it.
--
-- price_tier_reminders_sent: one row per (subscription, tier) a message was
-- claimed for, INSERTed before the send (ON CONFLICT DO NOTHING), so each
-- subscriber hears about each tier at most once, even with several replicas
-- and a crash mid-run (a crash loses a message, never repeats one).

CREATE TABLE event_reminder_subscriptions (
    id               UUID PRIMARY KEY,
    event_id         UUID         NOT NULL,
    phone_number     VARCHAR(32)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    unsubscribed_at  TIMESTAMP,
    CONSTRAINT uq_event_reminder_subscription UNIQUE (event_id, phone_number)
);

CREATE INDEX IF NOT EXISTS idx_event_reminder_subscriptions_phone
    ON event_reminder_subscriptions (phone_number) WHERE unsubscribed_at IS NULL;

CREATE TABLE price_tier_reminders_sent (
    subscription_id  UUID      NOT NULL REFERENCES event_reminder_subscriptions (id) ON DELETE CASCADE,
    price_tier_id    UUID      NOT NULL,
    sent_at          TIMESTAMP NOT NULL,
    PRIMARY KEY (subscription_id, price_tier_id)
);
