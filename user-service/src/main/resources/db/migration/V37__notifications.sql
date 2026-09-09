-- Notifications: the in-app bell, replacing the console's three-list poll.
--
-- Why a table rather than deriving the bell from existing queries: the console
-- fabricated its badge by fetching /admin/users, /admin/service-requests and
-- /events/inactive every 60 seconds and counting rows. That can only ever know
-- about the three things someone thought to poll, has no read state (so a badge
-- reappears on every device and never acknowledges being seen), and reaches no
-- non-admin at all -- an organizer was never told their event was approved.
--
-- recipient_uuid is users.user_uuid, deliberately NOT a FK: notifications are
-- written by other services through the S2S ingress for a uuid they hold, and a
-- notification about a user who is later deleted is history worth keeping, not
-- a row to cascade away.
CREATE TABLE notifications (
    id               UUID         PRIMARY KEY,
    recipient_uuid   UUID         NOT NULL,
    type             VARCHAR(64)  NOT NULL,
    title            VARCHAR(200) NOT NULL,
    body             VARCHAR(2000) NOT NULL,
    severity         VARCHAR(16)  NOT NULL,
    -- Who caused it. Nullable: a system-generated notice has no actor.
    actor_id         VARCHAR(64),
    actor_name       VARCHAR(200),
    -- What it is about, so a client can de-duplicate and refresh the right
    -- screen instead of reloading everything. subject_id is VARCHAR because
    -- subjects are not all UUIDs -- a service request id is a bigint.
    subject_kind     VARCHAR(64),
    subject_id       VARCHAR(64),
    -- Where to go. Supplied by the server so the console does not maintain a
    -- client-side type -> route map that goes stale silently.
    deep_link        VARCHAR(500),
    created_at       TIMESTAMP    NOT NULL,
    read_at          TIMESTAMP
);

-- The list endpoint: newest first for one recipient.
CREATE INDEX idx_notifications_recipient_created
    ON notifications (recipient_uuid, created_at DESC);

-- The badge. Partial index because the unread set is the small, hot one --
-- a full index on recipient_uuid would carry every read row forever for a
-- query that never wants them.
CREATE INDEX idx_notifications_unread
    ON notifications (recipient_uuid)
    WHERE read_at IS NULL;
