-- Marks a refresh family that was born from a PHONE PROOF rather than a
-- password login: today, POST /auth/exchange.
--
-- Why this has to live on the refresh ROW rather than in the access token.
-- /auth/exchange scopes the session it issues down to CUSTOMER, because an
-- assertion proves possession of a phone and nothing more. But /auth/refresh
-- re-reads the LIVE user and re-derives the claims from their current roles,
-- so without a marker that survives rotation the very first refresh would
-- silently hand back every role the account holds — undoing the scoping one
-- request later. Exactly the shape of the gate-operator hole: a decision taken
-- at login that the refresh path never learned about.
--
-- Carried through a rotation the same way device_id_hash is: stamped once when
-- the family is minted, copied onto every successor, never re-derived from the
-- user. A family is phone-proof for its whole life.
--
-- DEFAULT FALSE is the safe direction for the rows already in flight: every
-- existing family came from a password login (or an OTP flow that mints its
-- own scoped token elsewhere), so false is the truth for all of them rather
-- than a guess.
ALTER TABLE refresh_tokens
    ADD COLUMN phone_proof BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN refresh_tokens.phone_proof IS
    'TRUE when this family was born from a phone-proof login (POST /auth/exchange). '
    'Sessions in such a family are scoped to CUSTOMER on every mint, including refresh, '
    'because the assertion proves phone possession and not staff authority.';
