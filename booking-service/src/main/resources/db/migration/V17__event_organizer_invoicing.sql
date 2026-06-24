-- Platform -> event-organizer fee invoicing.
--
-- The platform periodically (per the organizer's billing cycle) invoices each
-- EVENT_ORGANIZER a commission on the ticket revenue they earned through the
-- platform. Revenue is recognised from CONFIRMED bookings only (a booking
-- confirms after payment lands), scoped by bookings.tenant_user_uuid == the
-- organizer's stable user_uuid. The commission rate + VAT rate are snapshotted
-- onto every invoice at generation time so a later rate change never rewrites
-- history.
--
-- Three tables:
--   organizer_billing_config  per-organizer commission rate + cycle (override
--                             of the deployment default)
--   event_invoices            one invoice per (organizer, billing period)
--   event_invoice_line_items  per-event breakdown of an invoice
--
-- Money is NUMERIC(19,2) (currency minor-unit precision); rates are NUMERIC(7,4)
-- percentages (e.g. 10.0000 = 10%). Timestamps are TIMESTAMP WITHOUT TIME ZONE
-- written in UTC, matching the rest of booking-service (see CLAUDE.md).

CREATE TABLE organizer_billing_config (
    organizer_uuid    UUID PRIMARY KEY,
    commission_rate   NUMERIC(7, 4)               NOT NULL,
    billing_cycle     VARCHAR(20)                 NOT NULL DEFAULT 'MONTHLY',
    currency          VARCHAR(8)                  NOT NULL DEFAULT 'USD',
    created_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT chk_obc_billing_cycle CHECK (billing_cycle IN ('WEEKLY', 'MONTHLY')),
    CONSTRAINT chk_obc_commission_rate CHECK (commission_rate >= 0 AND commission_rate <= 100)
);

-- Global, monotonic source for human-readable invoice numbers (INV-<year>-<seq>).
-- Sequences are non-transactional, so a rolled-back generation can leave a gap;
-- gaps in invoice numbers are acceptable here (we never claim gapless billing).
CREATE SEQUENCE event_invoice_number_seq START 1 INCREMENT 1;

CREATE TABLE event_invoices (
    id                 UUID PRIMARY KEY,
    invoice_number     VARCHAR(40)                 NOT NULL UNIQUE,
    organizer_uuid     UUID                        NOT NULL,
    period_start       DATE                        NOT NULL,
    period_end         DATE                        NOT NULL,
    status             VARCHAR(20)                 NOT NULL DEFAULT 'ISSUED',
    currency           VARCHAR(8)                  NOT NULL DEFAULT 'USD',
    confirmed_bookings BIGINT                      NOT NULL DEFAULT 0,
    tickets_sold       BIGINT                      NOT NULL DEFAULT 0,
    gross_sales        NUMERIC(19, 2)              NOT NULL DEFAULT 0,
    commission_rate    NUMERIC(7, 4)               NOT NULL,
    commission_amount  NUMERIC(19, 2)              NOT NULL,
    tax_rate           NUMERIC(7, 4)               NOT NULL,
    tax_amount         NUMERIC(19, 2)              NOT NULL,
    total_amount       NUMERIC(19, 2)              NOT NULL,
    issued_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    due_at             TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    paid_at            TIMESTAMP WITHOUT TIME ZONE,
    cancelled_at       TIMESTAMP WITHOUT TIME ZONE,
    created_at         TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITHOUT TIME ZONE,
    -- One invoice per organizer per billing window. The unique constraint is the
    -- idempotency backstop: a re-run of the generator (scheduler ticking daily
    -- within the same period, or an admin manual run racing the scheduler) hits
    -- this and is treated as "already generated".
    CONSTRAINT uq_event_invoice_org_period UNIQUE (organizer_uuid, period_start, period_end),
    CONSTRAINT chk_ei_status CHECK (status IN ('ISSUED', 'PAID', 'OVERDUE', 'CANCELLED')),
    CONSTRAINT chk_ei_period CHECK (period_end >= period_start),
    CONSTRAINT chk_ei_amounts_nonneg CHECK (
        gross_sales >= 0 AND commission_amount >= 0 AND tax_amount >= 0 AND total_amount >= 0
    )
);

CREATE INDEX idx_event_invoices_organizer ON event_invoices (organizer_uuid);
CREATE INDEX idx_event_invoices_status ON event_invoices (status);
CREATE INDEX idx_event_invoices_issued_at ON event_invoices (issued_at);

CREATE TABLE event_invoice_line_items (
    id                 UUID PRIMARY KEY,
    invoice_id         UUID                        NOT NULL REFERENCES event_invoices (id) ON DELETE CASCADE,
    event_id           UUID                        NOT NULL,
    tickets_sold       BIGINT                      NOT NULL DEFAULT 0,
    confirmed_bookings BIGINT                      NOT NULL DEFAULT 0,
    gross_sales        NUMERIC(19, 2)              NOT NULL DEFAULT 0,
    commission_amount  NUMERIC(19, 2)              NOT NULL DEFAULT 0,
    CONSTRAINT chk_eili_amounts_nonneg CHECK (gross_sales >= 0 AND commission_amount >= 0)
);

CREATE INDEX idx_event_invoice_line_items_invoice ON event_invoice_line_items (invoice_id);
