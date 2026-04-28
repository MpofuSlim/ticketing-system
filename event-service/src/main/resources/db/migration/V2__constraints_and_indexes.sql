-- Defence-in-depth invariants and lookup indexes for event-service.

ALTER TABLE events
    ADD CONSTRAINT chk_events_capacity_nonneg
        CHECK (total_capacity >= 0),
    ADD CONSTRAINT chk_events_available_nonneg
        CHECK (available_tickets >= 0),
    ADD CONSTRAINT chk_events_available_lte_capacity
        CHECK (available_tickets <= total_capacity),
    ADD CONSTRAINT chk_events_province
        CHECK (province IN ('HRE', 'BYO', 'MID', 'MNL', 'MCT',
                            'MET', 'MWT', 'MSV', 'MTN', 'MTS'));

CREATE INDEX IF NOT EXISTS idx_events_tenant
    ON events (tenant_id);

CREATE INDEX IF NOT EXISTS idx_events_province_active
    ON events (province) WHERE deleted = FALSE AND active = TRUE;

CREATE INDEX IF NOT EXISTS idx_events_date_time
    ON events (date_time);
