-- Price-change history (append-only): one row each time a category's price or
-- its price tiers change, written in the same transaction as the change.
--
--   CREATED        the category was created: new_price, tiers_after.
--   PRICE_CHANGED  PUT /seat-categories/{id} moved the full price:
--                  old_price, new_price (the tiers did not change).
--   TIERS_CHANGED  PUT /seat-categories/{id}/price-tiers: tiers_before,
--                  tiers_after (the full price did not change).
--
-- tiers_* hold a JSON array of each tier's terms (name, price, startsAt,
-- endsAt, quantity, maxPerCustomer, refundsReopen; dates as UTC wall clock,
-- no zone) as they were, so a renamed or deleted tier still reads as it was.
-- changed_by is the JPA auditor (organizer uuid or JWT email), as on V8.
--
-- category_id has NO foreign key on purpose: the record must outlive the row
-- it describes, and a hard delete of a category (the ITs clean up that way)
-- must not be blocked by its own history. The trigger refuses UPDATE and
-- DELETE, so no code path can rewrite who changed a price, or when.

CREATE TABLE seat_category_price_changes (
    id            UUID PRIMARY KEY,
    category_id   UUID          NOT NULL,
    event_id      UUID          NOT NULL,
    change_type   VARCHAR(20)   NOT NULL,
    old_price     NUMERIC(10,2),
    new_price     NUMERIC(10,2),
    tiers_before  TEXT,
    tiers_after   TEXT,
    changed_by    VARCHAR(255),
    changed_at    TIMESTAMP     NOT NULL,
    CONSTRAINT chk_price_change_type CHECK (change_type IN ('CREATED', 'PRICE_CHANGED', 'TIERS_CHANGED'))
);

CREATE INDEX idx_price_changes_category_at ON seat_category_price_changes (category_id, changed_at);

CREATE FUNCTION seat_category_price_changes_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'seat_category_price_changes is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER seat_category_price_changes_append_only
    BEFORE UPDATE OR DELETE ON seat_category_price_changes
    FOR EACH ROW EXECUTE FUNCTION seat_category_price_changes_append_only();
