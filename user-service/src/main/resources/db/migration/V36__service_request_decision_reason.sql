-- Rejecting a service request.
--
-- Until now the only decision an admin could record was APPROVED: there was no
-- reject endpoint, and Status had no REJECTED value, so a request an admin
-- decided against stayed PENDING forever and the queue could never drain. The
-- console has been rendering and filtering a REJECTED status the database could
-- not store.
--
-- Two notes on what is deliberately NOT here:
--
--   1. No constraint change is needed for the new status. V4 declared
--      status as a bare VARCHAR(32) with no CHECK, so 'REJECTED' is already a
--      legal value at the DB layer — the enum in ServiceRequest.Status is the
--      only thing that was refusing it.
--
--   2. The partial unique index uk_service_requests_user_service_pending is
--      scoped `WHERE status = 'PENDING'`, so a REJECTED row does not block the
--      same user re-requesting the same bundle later. That is the behaviour we
--      want — a rejection is a decision on one request, not a permanent ban —
--      and it needs no change here.

-- The reviewer's stated reason for the decision.
--
-- This is NOT the same field as service_requests.reason, which is the
-- REQUESTER's justification captured at submission and is NOT NULL. Conflating
-- the two would overwrite the applicant's own words with the admin's, so the
-- decision gets its own nullable column: null on every pre-existing row, and on
-- an approval where the reviewer added no note.
ALTER TABLE service_requests
    ADD COLUMN IF NOT EXISTS decision_reason VARCHAR(1000);
