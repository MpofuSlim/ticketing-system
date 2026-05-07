-- Service requests: an EVENT_ORGANIZER / MERCHANT_ADMIN's request to be granted
-- access to an additional default service bundle. SUPER_ADMIN reviews these
-- via /admin/service-requests and approves them, which adds the bundle to the
-- user's defaultServices and grants the matching role.

CREATE TABLE IF NOT EXISTS service_requests (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    service      VARCHAR(255) NOT NULL,
    reason       VARCHAR(1000) NOT NULL,
    status       VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at  TIMESTAMP,
    reviewed_by  BIGINT,
    CONSTRAINT fk_service_requests_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_service_requests_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_service_requests_user_id ON service_requests (user_id);
CREATE INDEX IF NOT EXISTS idx_service_requests_status  ON service_requests (status);

-- A user cannot have two simultaneously pending requests for the same bundle.
-- Implemented as a partial unique index so historical APPROVED rows for the
-- same (user_id, service) don't clash with future re-requests.
CREATE UNIQUE INDEX IF NOT EXISTS uk_service_requests_user_service_pending
    ON service_requests (user_id, service)
    WHERE status = 'PENDING';
