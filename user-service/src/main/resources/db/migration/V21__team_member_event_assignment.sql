-- Per-event restriction for team members.
--
-- Default scoping (V20) is organizer-wide: a TEAM_MEMBER can scan every event
-- their parent organizer owns. This table narrows that — a member with one or
-- more assignment rows may scan ONLY the assigned events. A member with NO
-- rows keeps the original organizer-wide behaviour (so existing team members
-- are unaffected until an organizer starts assigning them).
--
-- Wait — that "no rows = wide open" rule is a deliberate product call, not an
-- oversight: it makes the feature additive (no migration of existing members)
-- and matches the mental model "assign to restrict". The scan-time check in
-- booking-service implements it: only enforce assignments when the member has
-- at least one.
--
-- team_member_user_uuid -> users.user_uuid (the stable cross-service id, not
-- the BIGINT pk). ON DELETE CASCADE so a hard-deleted user takes their
-- assignments with them — but the normal lifecycle is soft-delete
-- (active=false), which leaves the rows intact so re-enabling a member
-- restores their assignments.

CREATE TABLE team_member_event_assignment (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_member_user_uuid       UUID NOT NULL REFERENCES users(user_uuid) ON DELETE CASCADE,
    -- event_id is event-service's UUID. No FK — events live in another
    -- service/database. An assignment to an event the organizer doesn't own
    -- is harmless: booking-service's organizer-wide check still blocks the
    -- scan, so the worst case is a meaningless row.
    event_id                    UUID NOT NULL,
    -- Who created the assignment (the organizer's user_uuid). Audit only.
    assigned_by_organizer_uuid  UUID NOT NULL,
    created_at                  TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_team_member_event UNIQUE (team_member_user_uuid, event_id)
);

-- The hot lookup: "which events is this member assigned to" (organizer's
-- management view) and "is this member assigned to event E" (scan-time check).
CREATE INDEX idx_tme_team_member ON team_member_event_assignment(team_member_user_uuid);
