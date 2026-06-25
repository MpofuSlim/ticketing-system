-- Single-use ledger for customer KYC-upgrade verification tokens.
--
-- The token (see KycVerificationTokenService) is a stateless, short-lived JWT
-- minted by POST /auth/otp/verify once a customer proves phone ownership, and
-- required on the otherwise-unauthenticated tier-2/3/4 registration endpoints.
-- The terminal tier-4 "mark verified" step records the token's jti here; a
-- replay with the same jti collides on the primary key and is rejected as
-- REPLAYED. Rows are pruned hourly once their expires_at has passed (an expired
-- token is rejected on the exp check anyway).
CREATE TABLE IF NOT EXISTS consumed_kyc_verification_token (
    jti         UUID        PRIMARY KEY,
    consumed_at TIMESTAMP   NOT NULL,
    expires_at  TIMESTAMP   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_consumed_kyc_token_expires
    ON consumed_kyc_verification_token (expires_at);
