-- Per-event settlement code: the short [A-Z0-9]{3,12} tag payment-service
-- embeds in every InnBucks code-generation reference (TKZ-<CODE>-<shortId>)
-- so the merchant statement can be grouped/settled per event by prefix
-- filtering. Nullable: pre-existing events have no code until one is set via
-- PUT /events/{id} (payment-service falls back to a short event-id tag).
-- New events auto-derive one from the title at creation when not supplied.
-- Deliberately NOT unique — two events sharing a code merely merge their
-- statement grouping, and organizers may legitimately reuse a series code
-- (e.g. an annual run) across editions they settle together.
ALTER TABLE events
    ADD COLUMN settlement_code VARCHAR(12);
