-- Persist the user's home_country (ISO 3166-1 alpha-2) on the users row, and
-- switch the unique constraint from (phone_number) to (phone_number,
-- home_country). Step 4 of the multi-cell deployment roadmap.
--
-- Today every existing row's home_country is the deployment country (ZW for
-- the current single-cell deployment) because there's only one cell, so the
-- new composite constraint is functionally identical to the old single-column
-- one. The change is forward-looking: cell #2 onwards has its own DB and its
-- own rows; each cell's home_country reflects where the customer is anchored,
-- and the column is what JWT mint, audit, and per-country slice queries read
-- without having to re-derive from the MSISDN every time.
--
-- For backfill on existing rows we derive home_country from the MSISDN
-- dialling prefix — the same ten-market table MsisdnCountryResolver carries
-- in Java code. Foreign / non-InnBucks MSISDNs (rare) fall back to the
-- deployment country (the Flyway placeholder `innbucks_country`, default ZW)
-- so the new NOT NULL constraint is satisfied without rejecting any historical
-- registration.

-- 1. Nullable column for the row-by-row backfill below.
ALTER TABLE users ADD COLUMN home_country VARCHAR(2);

-- 2. Backfill from MSISDN prefix. Mirror the longest-prefix-first ordering
-- of MsisdnCountryResolver — South Africa (+27) is the only 2-digit prefix
-- and is checked AFTER all 3-digit prefixes so a hypothetical future 3-digit
-- entry starting with "27" wouldn't be shadowed.
UPDATE users SET home_country = CASE
    WHEN phone_number LIKE '+234%' OR phone_number LIKE '234%' THEN 'NG'
    WHEN phone_number LIKE '+254%' OR phone_number LIKE '254%' THEN 'KE'
    WHEN phone_number LIKE '+258%' OR phone_number LIKE '258%' THEN 'MZ'
    WHEN phone_number LIKE '+260%' OR phone_number LIKE '260%' THEN 'ZM'
    WHEN phone_number LIKE '+263%' OR phone_number LIKE '263%' THEN 'ZW'
    WHEN phone_number LIKE '+265%' OR phone_number LIKE '265%' THEN 'MW'
    WHEN phone_number LIKE '+266%' OR phone_number LIKE '266%' THEN 'LS'
    WHEN phone_number LIKE '+267%' OR phone_number LIKE '267%' THEN 'BW'
    WHEN phone_number LIKE '+268%' OR phone_number LIKE '268%' THEN 'SZ'
    WHEN phone_number LIKE '+27%'  OR phone_number LIKE '27%'  THEN 'ZA'
    ELSE '${innbucks_country}'
END
WHERE home_country IS NULL;

-- 3. Enforce NOT NULL now that every row carries a value.
ALTER TABLE users ALTER COLUMN home_country SET NOT NULL;

-- 4. Drop the old single-column unique constraint. Name is the one declared
-- in V1__init.sql; using IF EXISTS so re-runs on partially-migrated DBs
-- don't fail.
ALTER TABLE users DROP CONSTRAINT IF EXISTS uk_users_phone_number;

-- 5. New composite uniqueness. The same MSISDN may legally exist in two
-- cells (different home_country values), but never twice within one cell.
ALTER TABLE users ADD CONSTRAINT uk_users_phone_country
    UNIQUE (phone_number, home_country);

-- 6. Per-country slice index. Backs operator listings ("all KE customers
-- registered last month") and the eventual per-cell reconciliation job
-- without forcing a full table scan.
CREATE INDEX idx_users_home_country ON users(home_country);
