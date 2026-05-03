-- Initial schema for loyalty-service.
-- Owned by Flyway; Hibernate boots in validate mode against this layout.

CREATE TABLE IF NOT EXISTS loyalty_rules (
    id              BIGSERIAL       PRIMARY KEY,
    tenant_id       VARCHAR(255)    NOT NULL,
    earn_rate       NUMERIC(10, 4)  NOT NULL,
    redeem_rate     NUMERIC(10, 4)  NOT NULL,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    CONSTRAINT uk_loyalty_rules_tenant UNIQUE (tenant_id),
    CONSTRAINT chk_loyalty_rules_earn_positive   CHECK (earn_rate   > 0),
    CONSTRAINT chk_loyalty_rules_redeem_positive CHECK (redeem_rate > 0)
);

CREATE TABLE IF NOT EXISTS loyalty_accounts (
    id              BIGSERIAL       PRIMARY KEY,
    customer_id     VARCHAR(255)    NOT NULL,
    tenant_id       VARCHAR(255)    NOT NULL,
    balance         NUMERIC(18, 4)  NOT NULL DEFAULT 0,
    version         BIGINT,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    CONSTRAINT uk_loyalty_accounts_customer_tenant UNIQUE (customer_id, tenant_id),
    CONSTRAINT chk_loyalty_accounts_balance_nonneg CHECK (balance >= 0)
);

CREATE TABLE IF NOT EXISTS loyalty_transactions (
    id              BIGSERIAL       PRIMARY KEY,
    account_id      BIGINT          NOT NULL,
    type            VARCHAR(16)     NOT NULL,
    points          NUMERIC(18, 4)  NOT NULL,
    dollar_amount   NUMERIC(18, 4),
    reference       VARCHAR(255)    NOT NULL,
    created_at      TIMESTAMP       NOT NULL,
    CONSTRAINT fk_loyalty_tx_account
        FOREIGN KEY (account_id) REFERENCES loyalty_accounts (id),
    CONSTRAINT uk_loyalty_tx_account_type_reference
        UNIQUE (account_id, type, reference),
    CONSTRAINT chk_loyalty_tx_type
        CHECK (type IN ('EARN', 'REDEEM')),
    CONSTRAINT chk_loyalty_tx_points_positive
        CHECK (points > 0)
);

CREATE INDEX IF NOT EXISTS idx_loyalty_tx_account_created
    ON loyalty_transactions (account_id, created_at DESC);
