-- Loyalty integration: associate each booking with the owning tenant (so
-- earn/redeem can be attributed) and record the points/cash split paid at
-- confirm time. All nullable; legacy bookings created before this column
-- existed simply have NULLs and skip loyalty interactions.

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS tenant_id    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS points_used  NUMERIC(18, 4),
    ADD COLUMN IF NOT EXISTS cash_amount  NUMERIC(10, 2);
