-- Super-app gate scanners: a PHONE NUMBER on an organizer's team, not an account.
--
-- The scanner-app TEAM_MEMBER (user-service) is an account with a temporary
-- password, one organizer per account, one account per phone. A super-app user
-- already has an account on their number, so none of that fits: they sign in at
-- the InnBucks middleware (/auth/exchange) and scan with that session. Here an
-- organizer names a number; the person behind it accepts in the super app,
-- which binds the row to the account that accepted (account_user_uuid), and
-- only then scans. One number may be on several organizers' teams (one row
-- each), but never on two events whose times overlap (checked in the service
-- under a lock on every row of the number; see ScannerService).
--
-- Status is derived, never stored: disabled_at > declined_at > accepted_at >
-- invited. organizer_uuid is user-service's users.user_uuid (no FK: another
-- database), like events.tenant_user_uuid.
CREATE TABLE event_scanners (
    scanner_id          UUID            PRIMARY KEY,
    organizer_uuid      UUID            NOT NULL,
    phone_number        VARCHAR(20)     NOT NULL,
    first_name          VARCHAR(100)    NOT NULL,
    last_name           VARCHAR(100)    NOT NULL,
    account_user_uuid   UUID,
    accepted_at         TIMESTAMP,
    declined_at         TIMESTAMP,
    disabled_at         TIMESTAMP,
    created_at          TIMESTAMP       NOT NULL,
    created_by          VARCHAR(255),
    updated_at          TIMESTAMP       NOT NULL,
    version             BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_event_scanners_organizer_phone UNIQUE (organizer_uuid, phone_number),
    -- Accepting binds the account that accepted, and leaving clears both, so a
    -- recycled number's new owner never inherits a team.
    CONSTRAINT chk_event_scanners_accept_binds_account
        CHECK ((accepted_at IS NULL) = (account_user_uuid IS NULL))
);

-- The clash check and the invite list read every row of one number.
CREATE INDEX idx_event_scanners_phone ON event_scanners (phone_number);
-- The gate list and the scan check read an account's accepted rows.
CREATE INDEX idx_event_scanners_account ON event_scanners (account_user_uuid)
    WHERE account_user_uuid IS NOT NULL;

CREATE TABLE event_scanner_assignments (
    assignment_id   UUID        PRIMARY KEY,
    scanner_id      UUID        NOT NULL REFERENCES event_scanners (scanner_id) ON DELETE CASCADE,
    event_id        UUID        NOT NULL REFERENCES events (event_id),
    assigned_at     TIMESTAMP   NOT NULL,
    assigned_by     VARCHAR(255),
    CONSTRAINT uq_event_scanner_assignments UNIQUE (scanner_id, event_id)
);

CREATE INDEX idx_event_scanner_assignments_event ON event_scanner_assignments (event_id);
