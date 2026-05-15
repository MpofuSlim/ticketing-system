-- Adds the tables and columns introduced when the User entity was refactored
-- from a single `role` VARCHAR to a Set<Role> ElementCollection (user_roles)
-- and gained a Set<String> defaultServices ElementCollection
-- (user_default_services) plus a boolean `active` flag.

-- active flag on users (new accounts start inactive until their phone is verified)
ALTER TABLE users ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT FALSE;

-- ElementCollection for User.roles  (replaces the old single `role` column)
CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT       NOT NULL,
    role    VARCHAR(255) NOT NULL,
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_user_roles UNIQUE (user_id, role)
);

CREATE INDEX IF NOT EXISTS idx_user_roles_user_id ON user_roles (user_id);

-- ElementCollection for User.defaultServices
CREATE TABLE IF NOT EXISTS user_default_services (
    user_id BIGINT       NOT NULL,
    service VARCHAR(255) NOT NULL,
    CONSTRAINT fk_user_default_services_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_user_default_services UNIQUE (user_id, service)
);

CREATE INDEX IF NOT EXISTS idx_user_default_services_user_id ON user_default_services (user_id);

-- Best-effort backfill: migrate the legacy single `role` column into user_roles
-- for any rows that were created before this migration ran.
INSERT INTO user_roles (user_id, role)
SELECT id,
       CASE role
           WHEN 'ADMIN'          THEN 'SUPER_ADMIN'
           WHEN 'SYSTEM_MANAGER' THEN 'SUPER_ADMIN'
           WHEN 'TENANT'         THEN 'EVENT_ORGANIZER'
           WHEN 'MERCHANT_ADMIN' THEN 'MERCHANT_ADMIN'
           WHEN 'CUSTOMER'       THEN 'CUSTOMER'
           ELSE NULL
       END
FROM users
WHERE role IS NOT NULL
  AND role IN ('ADMIN', 'SYSTEM_MANAGER', 'TENANT', 'MERCHANT_ADMIN', 'CUSTOMER')
ON CONFLICT DO NOTHING;
