-- DTX device security: the device registry, OTP challenges, login tickets and
-- the decision log behind "Device Registration, Fraud Detection and Sign-In
-- Through DTX" (the InnBucks 2.0 app contract, v2.1).
--
-- Why every table is keyed by MSISDN rather than users.id: the customers this
-- protects authenticate at the banking core (staging), not here. Most of them
-- have no users row at the moment DTX first sees their phone, and DTX must
-- decide BEFORE anyone has proven anything. The E.164 number is the one
-- identity every party (app, broker, staging, USSD) agrees on.
--
-- Why the install id is HASHED: the install id is the device's identity until a
-- hardware id ships, so anyone who learns a trusted device's install id can
-- present it from another phone. It is never returned by any endpoint and never
-- stored raw; a DB read yields SHA-256 digests of 122-bit random UUIDs, which
-- are useless for impersonation. Same reasoning as refresh_tokens.token_hash.
--
-- Timestamps are zone-less TIMESTAMP holding UTC, per the service convention.

-- One row per (customer, device) pair. Exactly one state at a time (§4).
CREATE TABLE customer_devices (
    id                          BIGSERIAL        PRIMARY KEY,
    -- The deviceId the API exposes. Opaque on purpose: exposing the install id
    -- in "Your devices" would hand anyone signed in on one phone the identity
    -- of the customer's other, trusted phones.
    public_id                   UUID             NOT NULL,
    msisdn                      VARCHAR(20)      NOT NULL,
    install_id_hash             VARCHAR(64)      NOT NULL,
    state                       VARCHAR(16)      NOT NULL,
    -- Where a PENDING_PIN device goes back to when the PIN step fails (§4).
    prior_state                 VARCHAR(16),
    -- Why the device is in its state: the OTP reason for STEP_UP, the block
    -- reason for TEMP_BLOCKED, the ban reason for BANNED.
    state_reason                VARCHAR(32),
    platform                    VARCHAR(16),
    os_version                  VARCHAR(32),
    model                       VARCHAR(64),
    manufacturer                VARCHAR(64),
    app_version                 VARCHAR(32),
    label                       VARCHAR(120),
    first_seen_at               TIMESTAMP        NOT NULL,
    last_seen_at                TIMESTAMP        NOT NULL,
    -- Rounded to 3 decimals by the app (~110 m); never finer.
    last_seen_lat               DOUBLE PRECISION,
    last_seen_lng               DOUBLE PRECISION,
    last_seen_near              VARCHAR(64),
    last_ip                     VARCHAR(64),
    trusted_until               TIMESTAMP,
    -- First time this pair became TRUSTED. Starts the new-device cooling period.
    bound_at                    TIMESTAMP,
    cooling_until               TIMESTAMP,
    -- A PENDING_PIN device may be issued a fresh ticket without a second OTP
    -- until this instant, so a mistyped PIN costs a retry, not another SMS.
    pin_grace_until             TIMESTAMP,
    blocked_at                  TIMESTAMP,
    -- NULL on a TEMP_BLOCKED row means an integrity hold (no timer).
    blocked_until               TIMESTAMP,
    ussd_unlockable             BOOLEAN          NOT NULL DEFAULT FALSE,
    -- A fraud-desk suspicion ban is USSD-unlockable only after 24 hours.
    unlockable_after            TIMESTAMP,
    support_ref                 VARCHAR(16),
    last_unlocked_at            TIMESTAMP,
    consecutive_dead_challenges INT              NOT NULL DEFAULT 0,
    state_changed_at            TIMESTAMP        NOT NULL,
    state_changed_by            VARCHAR(255),
    created_at                  TIMESTAMP        NOT NULL,
    updated_at                  TIMESTAMP        NOT NULL,
    version                     BIGINT           NOT NULL DEFAULT 0,
    CONSTRAINT uk_customer_devices_public_id UNIQUE (public_id),
    CONSTRAINT uk_customer_devices_msisdn_install UNIQUE (msisdn, install_id_hash)
);

-- Cross-customer questions (§8.2): how many accounts has this phone touched?
CREATE INDEX idx_customer_devices_install ON customer_devices (install_id_hash);
-- The fraud desk's queue. Partial: the blocked set is small and hot.
CREATE INDEX idx_customer_devices_blocked
    ON customer_devices (state_changed_at DESC)
    WHERE state IN ('TEMP_BLOCKED', 'BANNED');

-- Per-customer facts that are not about one device.
CREATE TABLE customer_security_profiles (
    msisdn              VARCHAR(20)   PRIMARY KEY,
    -- The channel the customer last chose for an OTP; also where security
    -- notifications go first (§10).
    preferred_channel   VARCHAR(16),
    fraud_flagged_at    TIMESTAMP,
    fraud_flag_note     VARCHAR(1000),
    pin_issued_at       TIMESTAMP,
    last_sign_in_at     TIMESTAMP,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0
);

-- One OTP challenge (§5.2, §5.3, §11). The code itself is never stored: only
-- its HMAC under otp.hmac-secret, compared in constant time.
CREATE TABLE device_otp_challenges (
    id                  VARCHAR(40)   PRIMARY KEY,
    msisdn              VARCHAR(20)   NOT NULL,
    customer_device_id  BIGINT        NOT NULL
        REFERENCES customer_devices (id) ON DELETE CASCADE,
    install_id_hash     VARCHAR(64)   NOT NULL,
    purpose             VARCHAR(16)   NOT NULL,
    reason              VARCHAR(32)   NOT NULL,
    status              VARCHAR(16)   NOT NULL,
    channel             VARCHAR(16),
    code_hash           VARCHAR(64),
    attempts_left       INT           NOT NULL,
    resends_left        INT           NOT NULL,
    send_count          INT           NOT NULL DEFAULT 0,
    -- In-session step-up only (§5.5): what the customer is about to do.
    session_action      VARCHAR(32),
    request_ip          VARCHAR(64),
    request_id          VARCHAR(64),
    created_at          TIMESTAMP     NOT NULL,
    expires_at          TIMESTAMP     NOT NULL,
    sent_at             TIMESTAMP,
    resend_after        TIMESTAMP,
    closed_at           TIMESTAMP,
    version             BIGINT        NOT NULL DEFAULT 0
);

-- The three OTP ceilings (§11): per number, per device, per network address.
CREATE INDEX idx_device_otp_msisdn_created  ON device_otp_challenges (msisdn, created_at);
CREATE INDEX idx_device_otp_install_created ON device_otp_challenges (install_id_hash, created_at);
CREATE INDEX idx_device_otp_ip_created      ON device_otp_challenges (request_ip, created_at);

-- One login ticket (§3 rule 3): single use, two minutes, bound to one number,
-- one device and one purpose. The signed JWT itself is never stored.
CREATE TABLE device_login_tickets (
    jti                 VARCHAR(40)   PRIMARY KEY,
    msisdn              VARCHAR(20)   NOT NULL,
    -- NULL for a LOOKUP ticket, which binds nothing.
    customer_device_id  BIGINT
        REFERENCES customer_devices (id) ON DELETE CASCADE,
    install_id_hash     VARCHAR(64)   NOT NULL,
    purpose             VARCHAR(16)   NOT NULL,
    context             VARCHAR(16)   NOT NULL,
    -- Issued straight off an OTP verify. A correct OTP followed by wrong PINs
    -- until staging locks the PIN is the SIM-without-PIN signal (§8.4).
    otp_verified        BOOLEAN       NOT NULL DEFAULT FALSE,
    lat                 DOUBLE PRECISION,
    lng                 DOUBLE PRECISION,
    request_ip          VARCHAR(64),
    issued_at           TIMESTAMP     NOT NULL,
    expires_at          TIMESTAMP     NOT NULL,
    redeemed_at         TIMESTAMP,
    voided_at           TIMESTAMP,
    outcome             VARCHAR(16),
    outcome_at          TIMESTAMP,
    staging_code        VARCHAR(32)
);

-- "Three or more wrong PINs on this device in 24 hours" (§8.3).
CREATE INDEX idx_device_tickets_install_outcome ON device_login_tickets (install_id_hash, outcome_at);
CREATE INDEX idx_device_tickets_device ON device_login_tickets (customer_device_id);

-- Every decision, with the reason and the features that led to it (§3 rule 9),
-- kept 12 months so a disputed transaction can be reconstructed.
--
-- Deliberately NOT the tamper-evident audit_events chain: every sign-in and
-- every ~15-minute silent renewal writes here, and the chain serialises its
-- writers on audit_chain_head's row lock — at that volume the audit path would
-- become the sign-in bottleneck. The chain still gets every state change a
-- PERSON makes (support blocks/unlocks, USSD unlocks and blocks), which is the
-- set an auditor needs sealed.
--
-- device_id is the public id, deliberately NOT a foreign key: this history must
-- outlive the device row it describes.
CREATE TABLE device_security_events (
    id                  BIGSERIAL     PRIMARY KEY,
    occurred_at         TIMESTAMP     NOT NULL,
    event_type          VARCHAR(40)   NOT NULL,
    msisdn              VARCHAR(20),
    device_id           UUID,
    install_id_hash     VARCHAR(64),
    -- What the caller was told, and what the engine would have said. They
    -- differ only in watch mode (§14) — which is exactly what watch mode is
    -- for measuring.
    decision            VARCHAR(16),
    evaluated_decision  VARCHAR(16),
    reason              VARCHAR(32),
    support_ref         VARCHAR(16),
    actor_type          VARCHAR(16)   NOT NULL,
    actor_id            VARCHAR(255),
    channel             VARCHAR(16),
    purpose             VARCHAR(16),
    context             VARCHAR(16),
    request_id          VARCHAR(64),
    ussd_session_id     VARCHAR(100),
    ip_address          VARCHAR(64),
    risk_score          INT,
    -- Serialised JSON (TEXT, not JSONB, for the same reason as audit_events).
    features            TEXT,
    note                VARCHAR(1000)
);

CREATE INDEX idx_device_events_msisdn_time  ON device_security_events (msisdn, occurred_at DESC);
CREATE INDEX idx_device_events_install_time ON device_security_events (install_id_hash, occurred_at DESC);
CREATE INDEX idx_device_events_time         ON device_security_events (occurred_at);
CREATE INDEX idx_device_events_support_ref  ON device_security_events (support_ref)
    WHERE support_ref IS NOT NULL;

-- Bans that apply to the DEVICE for every customer (§8.5): SHARED_DEVICE (the
-- mule-farm pattern) and CONFIRMED_FRAUD. Every other ban is per pair and lives
-- on customer_devices.
CREATE TABLE device_wide_bans (
    install_id_hash     VARCHAR(64)   PRIMARY KEY,
    reason              VARCHAR(32)   NOT NULL,
    support_ref         VARCHAR(16)   NOT NULL,
    banned_at           TIMESTAMP     NOT NULL,
    banned_by           VARCHAR(255),
    note                VARCHAR(1000),
    lifted_at           TIMESTAMP,
    lifted_by           VARCHAR(255)
);

-- Rounded locations of SUCCESSFUL sign-ins only — the places the customer is
-- known to be (§8.1). A refused or abandoned attempt teaches nothing and would
-- let an attacker train the model with failed tries.
CREATE TABLE device_sign_in_locations (
    id                  BIGSERIAL        PRIMARY KEY,
    msisdn              VARCHAR(20)      NOT NULL,
    device_id           UUID,
    lat                 DOUBLE PRECISION NOT NULL,
    lng                 DOUBLE PRECISION NOT NULL,
    occurred_at         TIMESTAMP        NOT NULL
);

CREATE INDEX idx_device_locations_msisdn_time ON device_sign_in_locations (msisdn, occurred_at DESC);
