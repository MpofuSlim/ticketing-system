-- POS API keys: a machine credential for a merchant's point-of-sale backend
-- (e.g. GAAP's cloud), exchanged at POST /auth/pos/token for a short-lived
-- cashier-level (SHOP_USER) access token. Replaces a human login + 2FA per till.
--
-- Only the SHA-256 of the key is stored: the key is 32 random bytes, so there
-- is nothing to enumerate and a keyed hash would buy nothing (same reasoning
-- as refresh_tokens and staff_invites). key_prefix is the first characters of
-- the key, kept so an operator can tell keys apart; it is not a secret.
--
-- token_version is the key's session epoch: every token minted from the key
-- carries it, and revoking bumps it and publishes it to the shared
-- auth:tokenver:<id> Redis key, so loyalty refuses the key's live tokens at
-- once instead of at their expiry.
CREATE TABLE pos_api_keys (
    id            UUID          PRIMARY KEY,
    name          VARCHAR(100)  NOT NULL,
    key_prefix    VARCHAR(16)   NOT NULL,
    key_hash      VARCHAR(64)   NOT NULL,
    merchant_id   UUID          NOT NULL,
    shop_id       UUID          NULL,
    allowed_ips   VARCHAR(1000) NULL,
    token_version BIGINT        NOT NULL DEFAULT 0,
    created_by    VARCHAR(254)  NOT NULL,
    created_at    TIMESTAMP     NOT NULL,
    last_used_at  TIMESTAMP     NULL,
    revoked_at    TIMESTAMP     NULL,
    revoked_by    VARCHAR(254)  NULL,
    CONSTRAINT uq_pos_api_keys_hash UNIQUE (key_hash)
);

CREATE INDEX idx_pos_api_keys_merchant ON pos_api_keys (merchant_id);
