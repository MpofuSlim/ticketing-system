-- Lookup indexes from the optimization-checklist audit; EXPLAIN-checked on
-- Postgres 16 against 100k rows.

-- existsByEmailIgnoreCase / findAllByEmailIgnoreCase (registration, staff
-- checks, support search). Spring Data's IgnoreCase compares UPPER(email) =
-- UPPER(?), so the index is on UPPER(email) — one on LOWER(email), or on the
-- bare column, is never used by those queries.
CREATE INDEX IF NOT EXISTS idx_users_email_upper
    ON users (UPPER(email));

-- Role-holder lookups (findByAnyRole, holder counts, the role-removal bulk
-- token bump) filter user_roles by role. Its only indexes lead with user_id.
-- (role, user_id) also lets the holder-id subquery run index-only.
CREATE INDEX IF NOT EXISTS idx_user_roles_role
    ON user_roles (role, user_id);
