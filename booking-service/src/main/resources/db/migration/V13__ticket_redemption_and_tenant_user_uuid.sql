-- Ticket redemption columns + stable cross-service organizer uuid on bookings.
--
-- redeemed_at / redeemed_by_user_uuid / redeemed_by_name: written when an
-- EVENT_ORGANIZER or one of their TEAM_MEMBERs scans a ticket via
-- POST /tickets/scan. redeemed_by_name is denormalised on purpose — if
-- the team member is later soft-disabled (active=false in user-service)
-- or renamed, the rejection toast on a second scan still shows the
-- human name they were known by at scan time ("already scanned by
-- Tariro at 19:42"). That display contract has to survive the lifecycle
-- of the team-member row.
--
-- The (redeemed_at IS NULL) partial UNIQUE-style guard is unnecessary —
-- redemption is single-shot per booking_item, enforced atomically by an
-- UPDATE ... WHERE redeemed_at IS NULL. A row whose first UPDATE landed
-- is forever excluded from the WHERE clause; a second scan touches 0
-- rows and the service returns ALREADY_REDEEMED with the original
-- audit fields.
--
-- bookings.tenant_user_uuid mirrors events.tenant_user_uuid so the scan
-- handler can authorize "scanner's organizerUuid == event's
-- tenantUserUuid" without a cross-service call. Captured from event-
-- service via EventLookupDTO at booking creation (dual-written
-- alongside the legacy tenantId email). Nullable for legacy rows
-- that pre-date this column; null disables the team-member scan path
-- for that booking and falls back to organizer-only via the
-- email-based tenantId.

ALTER TABLE bookings
    ADD COLUMN tenant_user_uuid UUID;

CREATE INDEX idx_bookings_tenant_user_uuid
    ON bookings(tenant_user_uuid)
    WHERE tenant_user_uuid IS NOT NULL;

ALTER TABLE booking_items
    ADD COLUMN redeemed_at        TIMESTAMP,
    ADD COLUMN redeemed_by_user_uuid UUID,
    ADD COLUMN redeemed_by_name   VARCHAR(255);

-- Index the redemption lookup so the "show me every ticket already
-- scanned for this event" admin view stays fast as the table grows.
CREATE INDEX idx_booking_items_redeemed_at
    ON booking_items(redeemed_at)
    WHERE redeemed_at IS NOT NULL;
