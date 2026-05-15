-- Reshape Tier-2 customer profile to match the new external request shape.
--
-- Changes:
--   * Rename id_number -> national_id (clearer name; same semantics).
--   * Drop passport_number and selfie_picture entirely — tier 2 no longer
--     collects them.
--   * Replace the flat `address` string with four structured columns
--     (street1 / city / post_code / country).
--   * Add `date_of_birth` (LocalDate).
--   * Add `client_custom_fields` (TEXT, serialized JSON map).

ALTER TABLE customer_profiles
    RENAME COLUMN id_number TO national_id;

ALTER TABLE customer_profiles
    DROP COLUMN IF EXISTS passport_number,
    DROP COLUMN IF EXISTS selfie_picture,
    DROP COLUMN IF EXISTS address;

ALTER TABLE customer_profiles
    ADD COLUMN address_street1   VARCHAR(255),
    ADD COLUMN address_city      VARCHAR(255),
    ADD COLUMN address_post_code VARCHAR(64),
    ADD COLUMN address_country   VARCHAR(255),
    ADD COLUMN date_of_birth     DATE,
    ADD COLUMN client_custom_fields TEXT;
