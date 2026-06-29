-- 2FA / TOTP on login.
--
-- The users table already carries mfa_enabled (boolean) + mfa_secret (varchar)
-- from V1 — they were placeholders that nothing wrote to. This migration:
--   1) Reshapes mfa_secret to TEXT so AES-GCM ciphertext (longer than the raw
--      base32 secret) fits. Existing rows are NULL today so no data conversion
--      is needed.
--   2) Adds mfa_backup_codes: 10 single-use recovery codes per user (bcrypt
--      hashes only — the plaintext is shown once at enrollment then thrown
--      away). PK on (user_id, code_hash) so a duplicate bcrypt hash (only
--      possible if two users happen to share the same plaintext, which they
--      won't) couldn't sneak in twice.
--   3) Adds AUTH_MFA_* audit-event types via the application enum, NOT a DB
--      check — audit_events stores the type as plain VARCHAR.
--
-- mfa_secret is encrypted at rest using MFA_ENCRYPTION_KEY (see MfaSecretCipher
-- + the AttributeConverter on the User entity).

ALTER TABLE users
    ALTER COLUMN mfa_secret TYPE TEXT;

CREATE TABLE mfa_backup_codes (
    id          BIGSERIAL                  PRIMARY KEY,
    user_id     BIGINT                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- bcrypt hash of the single-use code (string form, including the $2a$ prefix).
    code_hash   VARCHAR(72)                NOT NULL,
    -- Set the moment the code is consumed; NULL while unused. The verifyForLogin
    -- path does an atomic UPDATE ... SET used_at=now() WHERE id=? AND used_at IS
    -- NULL, so a code can win exactly one race.
    used_at     TIMESTAMP WITHOUT TIME ZONE,
    created_at  TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    -- A user only ever has one active set of 10 codes, but the same plaintext
    -- code (after bcrypt) is theoretically reusable across users. Scope the
    -- uniqueness to (user_id, code_hash) rather than code_hash alone.
    CONSTRAINT uq_mfa_backup_codes_user_code UNIQUE (user_id, code_hash)
);

CREATE INDEX idx_mfa_backup_codes_user ON mfa_backup_codes (user_id);
