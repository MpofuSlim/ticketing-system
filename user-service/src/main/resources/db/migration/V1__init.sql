-- Initial schema for user-service.
-- Mirrors the entity-driven schema Hibernate previously generated via
-- ddl-auto=update. Once Flyway has run V1, Hibernate runs in validate mode
-- and any drift between an entity and the schema fails startup.

CREATE TABLE IF NOT EXISTS users (
    id              BIGSERIAL       PRIMARY KEY,
    first_name      VARCHAR(255)    NOT NULL,
    middle_name     VARCHAR(255),
    last_name       VARCHAR(255)    NOT NULL,
    phone_number    VARCHAR(255)    NOT NULL,
    email           VARCHAR(255),
    password        VARCHAR(255)    NOT NULL,
    role            VARCHAR(255),
    mfa_enabled     BOOLEAN         NOT NULL DEFAULT FALSE,
    mfa_secret      VARCHAR(255),
    created_at      TIMESTAMP,
    CONSTRAINT uk_users_phone_number UNIQUE (phone_number),
    CONSTRAINT uk_users_email        UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS customer_profiles (
    id                          BIGSERIAL       PRIMARY KEY,
    user_id                     BIGINT          NOT NULL,
    registration_tier           INTEGER         NOT NULL DEFAULT 1,
    full_name                   VARCHAR(255),
    id_number                   VARCHAR(255),
    passport_number             VARCHAR(255),
    address                     VARCHAR(255),
    gender                      VARCHAR(255),
    selfie_picture              TEXT,
    biometrics_reference        VARCHAR(255),
    id_document_path            VARCHAR(255),
    proof_of_residence_path     VARCHAR(255),
    passport_document_path      VARCHAR(255),
    verified                    BOOLEAN         NOT NULL DEFAULT FALSE,
    phone_verified              BOOLEAN         NOT NULL DEFAULT FALSE,
    updated_at                  TIMESTAMP,
    CONSTRAINT fk_customer_profiles_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_customer_profiles_user UNIQUE (user_id)
);

CREATE TABLE IF NOT EXISTS tenant_profiles (
    id                      BIGSERIAL       PRIMARY KEY,
    user_id                 BIGINT          NOT NULL,
    business_name           VARCHAR(255),
    business_address        VARCHAR(255),
    business_email          VARCHAR(255),
    business_phone_number   VARCHAR(255),
    registration_number     VARCHAR(255),
    meta_data_file_path     VARCHAR(255),
    total_events            INTEGER         NOT NULL DEFAULT 0,
    rating                  DOUBLE PRECISION NOT NULL DEFAULT 0,
    CONSTRAINT fk_tenant_profiles_user
        FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE IF NOT EXISTS devices (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    device_id       VARCHAR(255)    NOT NULL,
    device_name     VARCHAR(255),
    platform        VARCHAR(255),
    push_token      VARCHAR(255),
    registered_at   TIMESTAMP,
    CONSTRAINT fk_devices_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_devices_user_device UNIQUE (user_id, device_id)
);

CREATE TABLE IF NOT EXISTS otps (
    id                BIGSERIAL       PRIMARY KEY,
    phone_number      VARCHAR(255)    NOT NULL,
    code              VARCHAR(6)      NOT NULL,
    expires_at        TIMESTAMP       NOT NULL,
    failed_attempts   INTEGER         NOT NULL DEFAULT 0,
    created_at        TIMESTAMP       NOT NULL,
    CONSTRAINT uk_otps_phone_number UNIQUE (phone_number)
);

CREATE TABLE IF NOT EXISTS otp_retry_attempts (
    id                  BIGSERIAL       PRIMARY KEY,
    phone_number        VARCHAR(255)    NOT NULL,
    attempt_count       INTEGER         NOT NULL DEFAULT 0,
    window_starts_at    TIMESTAMP       NOT NULL,
    locked_until        TIMESTAMP,
    CONSTRAINT uk_otp_retry_attempts_phone UNIQUE (phone_number)
);

CREATE TABLE IF NOT EXISTS pending_registrations (
    id              BIGSERIAL       PRIMARY KEY,
    phone_number    VARCHAR(255)    NOT NULL,
    password_hash   VARCHAR(255)    NOT NULL,
    created_at      TIMESTAMP       NOT NULL,
    expires_at      TIMESTAMP       NOT NULL,
    CONSTRAINT uk_pending_registrations_phone UNIQUE (phone_number)
);

CREATE TABLE IF NOT EXISTS revoked_tokens (
    id            BIGSERIAL       PRIMARY KEY,
    token_hash    VARCHAR(64)     NOT NULL,
    expires_at    TIMESTAMP       NOT NULL,
    revoked_at    TIMESTAMP       NOT NULL,
    subject       VARCHAR(255),
    CONSTRAINT uk_revoked_tokens_hash UNIQUE (token_hash)
);
