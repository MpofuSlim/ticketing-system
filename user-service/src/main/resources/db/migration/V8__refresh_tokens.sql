-- Refresh-token rotation table.
--
-- Each row represents one refresh token issued to a user. When a refresh
-- token is presented at /auth/refresh:
--   * a fresh token is minted, linked to the same family_id and pointed at
--     by the rotated row via replaced_by_id;
--   * the rotated row is marked revoked_at = now().
-- If a previously-rotated (revoked_at IS NOT NULL) row is presented again,
-- that is treated as token theft and the entire family is revoked.
--
-- Only token_hash (SHA-256 of the raw JWT) is stored, never the token itself.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id              UUID            PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    token_hash      VARCHAR(64)     NOT NULL,
    family_id       UUID            NOT NULL,
    parent_id       UUID,
    replaced_by_id  UUID,
    expires_at      TIMESTAMP       NOT NULL,
    revoked_at      TIMESTAMP,
    created_at      TIMESTAMP       NOT NULL,
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_refresh_tokens_hash
    ON refresh_tokens (token_hash);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family
    ON refresh_tokens (family_id);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user
    ON refresh_tokens (user_id);
