-- Sales agents: people on an organizer's event team (event-service V14/V21,
-- event_scanners rows with can_sell) who sell tickets on the organizer's
-- behalf through POST /bookings/box-office, from the InnBucks app or the
-- Foundry portal. event-service answers, live, whether an account may sell
-- for an event (GET /events/internal/seller-access); these columns record
-- what it said at the sale.
--
-- bookings.sold_by_agent_id: the team row (event_scanners.scanner_id) the
--   sale was made under. NULL on every other booking, the organizer's own box
--   office sales included. Written at insert, never changed. With
--   sold_by_user_uuid (the agent's account) and sold_by_name (the name the
--   organizer gave the agent), it is what the agent's and the organizer's
--   sales-agent reports read.
-- bookings.agent_commission_percent: the agent's commission for the event
--   at the moment of the sale (0..100), written at insert, so a later change
--   by the organizer never restates a past sale.
-- bookings.agent_commission_amount: total_amount x percent / 100, HALF_UP to
--   the cent, written when the booking is CONFIRMED (a cash sale at once, an
--   online one when payment-service confirms it, a resurrected one too).
--   Reports count it only on CONFIRMED bookings: a voided or cancelled sale
--   earns nothing.
--
-- idx_bookings_sold_by_agent_event: the agent's ticket cap is counted under a
-- per-(agent, event) advisory lock on every agent sale, and the reports read
-- an agent's sales by it. Partial, so every other booking carries no entry.
--
-- Locking: Flyway runs this in one transaction and ADD COLUMN takes bookings'
-- ACCESS EXCLUSIVE lock until the commit, so bookings is unreadable while the
-- index is built and the CHECK validated (each one scan of bookings: seconds
-- at today's size). lock_timeout bounds the wait to TAKE the lock, so a busy
-- table fails the migration (the old pod serves on) rather than queueing every
-- booking request behind it. Deploy at a quiet time.
--
-- agent_cash_handovers: the cash an organizer records receiving from an agent.
-- Never deleted: a mistaken entry is soft-deleted (deleted_at/by), and the
-- reports skip it. event_id is optional (a handover for no event in
-- particular). received_at is when the cash changed hands (UTC).
--
-- Additive: nullable columns with no default are catalog-only changes, and a
-- new table. The previous image ignores all of it.
SET LOCAL lock_timeout = '10s';

ALTER TABLE bookings ADD COLUMN sold_by_agent_id         UUID;
ALTER TABLE bookings ADD COLUMN agent_commission_percent NUMERIC(5, 2);
ALTER TABLE bookings ADD COLUMN agent_commission_amount  NUMERIC(12, 2);

ALTER TABLE bookings ADD CONSTRAINT chk_bookings_agent_commission_percent
    CHECK (agent_commission_percent IS NULL OR agent_commission_percent BETWEEN 0 AND 100);

CREATE INDEX idx_bookings_sold_by_agent_event
    ON bookings (sold_by_agent_id, event_id)
    WHERE sold_by_agent_id IS NOT NULL;

CREATE TABLE agent_cash_handovers (
    id                    UUID           PRIMARY KEY,
    organizer_uuid        UUID           NOT NULL,
    agent_id              UUID           NOT NULL,
    event_id              UUID,
    amount                NUMERIC(12, 2) NOT NULL,
    currency              VARCHAR(3)     NOT NULL,
    received_at           TIMESTAMP      NOT NULL,
    recorded_by_user_uuid UUID,
    recorded_by           VARCHAR(254),
    note                  VARCHAR(500),
    created_at            TIMESTAMP      NOT NULL,
    deleted_at            TIMESTAMP,
    deleted_by_user_uuid  UUID,
    deleted_by            VARCHAR(254),
    CONSTRAINT chk_agent_cash_handovers_amount CHECK (amount > 0)
);

CREATE INDEX idx_agent_cash_handovers_organizer_agent
    ON agent_cash_handovers (organizer_uuid, agent_id, received_at);

CREATE INDEX idx_agent_cash_handovers_agent
    ON agent_cash_handovers (agent_id, received_at);
