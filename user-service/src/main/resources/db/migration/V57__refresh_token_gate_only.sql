-- Marks a refresh family born from the Organizer app's gate sign-in
-- (POST /auth/gate/sign-in): a phone number proved by an SMS code, and nothing
-- else about the person.
--
-- Sessions in such a family carry ONLY the GATE_SCANNER role, on every mint,
-- refresh included. booking-service and event-service confine that role to the
-- gate endpoints, and user-service refuses it everywhere. Same reason as
-- phone_proof (V38) for living on the row: /auth/refresh re-reads the live user
-- and re-derives the claims from their roles, so a scope decided at sign-in and
-- kept anywhere else would be gone after the first refresh.
--
-- Stamped once when the family is minted, copied onto every successor, never
-- re-derived. A gate family is also phone_proof, so the refresh MFA guard and
-- the organization switch treat it as they treat a phone proof.
--
-- GATE_SCANNER is the gate session's role and no account may hold it: the other
-- services confine any token carrying it to the gate endpoints. POST /admin/roles
-- now refuses the name; refuse here too if a role or an account already holds
-- it, rather than adopt it (the V43 / V55 rule). The pre-deploy check is the
-- same two SELECTs.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM roles WHERE UPPER(name) = 'GATE_SCANNER')
       OR EXISTS (SELECT 1 FROM user_roles WHERE UPPER(role) = 'GATE_SCANNER') THEN
        RAISE EXCEPTION 'V57: a role or an account already holds GATE_SCANNER, which is now reserved for the gate sign-in session. Remove it before deploying.';
    END IF;
END
$$;

-- DEFAULT FALSE is the truth for every row already in flight: none of them came
-- from a gate sign-in. Adding a defaulted column is a metadata-only change.
ALTER TABLE refresh_tokens
    ADD COLUMN gate_only BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN refresh_tokens.gate_only IS
    'TRUE when this family was born from the Organizer app gate sign-in (POST /auth/gate/sign-in). '
    'Sessions in such a family carry only the GATE_SCANNER role on every mint, including refresh.';
