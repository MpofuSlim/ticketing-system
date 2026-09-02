-- RBAC: roles become DATA (so an operator can create one at runtime) while
-- permissions stay a CODE-OWNED vocabulary.
--
-- The asymmetry is the whole design. A permission is only meaningful because
-- some @PreAuthorize names it, so a permission invented at runtime would grant
-- exactly nothing — the same inert-label trap the PRODUCT_OFFICER role sat in
-- (see User.Role's comment). Roles, by contrast, are just named bundles of
-- existing permissions, so composing a new one at runtime is genuinely useful
-- on the day it is created. Hence: POST /admin/roles exists; there is
-- deliberately no POST /admin/permissions.
--
-- `permissions` is therefore a MIRROR of the catalog in PermissionCatalog.java,
-- seeded here and re-upserted at boot by PermissionCatalogInitializer so a
-- later release that adds a permission does not need its own migration. It is a
-- real table (not just an enum) so role_permissions can carry a foreign key and
-- so GET /admin/permissions can list what is available to compose with.

CREATE TABLE IF NOT EXISTS permissions (
    code        VARCHAR(100) PRIMARY KEY,
    description VARCHAR(500) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- builtin roles are the nine that shipped as the User.Role enum. They are
-- undeletable and un-renamable: code still references them by name (literally,
-- in @PreAuthorize and in Services.BUNDLE_ROLES), so renaming one at runtime
-- would break authorization silently rather than loudly. Their PERMISSIONS are
-- editable — that is the supported way to adjust what a built-in role can do.
CREATE TABLE IF NOT EXISTS roles (
    name        VARCHAR(64) PRIMARY KEY,
    description VARCHAR(500) NOT NULL,
    builtin     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by  VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS role_permissions (
    role_name       VARCHAR(64)  NOT NULL,
    permission_code VARCHAR(100) NOT NULL,
    CONSTRAINT pk_role_permissions PRIMARY KEY (role_name, permission_code),
    CONSTRAINT fk_role_permissions_role
        FOREIGN KEY (role_name) REFERENCES roles (name) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_code) REFERENCES permissions (code) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_role_permissions_role ON role_permissions (role_name);

-- ---------------------------------------------------------------------------
-- Seed: the permission catalog.
--
-- Every code here is named by a @PreAuthorize in this service as of this
-- migration. `*` is the wildcard SUPER_ADMIN holds — see the note on the
-- SUPER_ADMIN seed below for why it is a wildcard rather than an enumeration.
-- ---------------------------------------------------------------------------
INSERT INTO permissions (code, description) VALUES
    ('*',                          'Every permission, including ones added by future releases. Reserved for SUPER_ADMIN.'),
    ('users:read',                 'List and read any user account'),
    ('users:merchants:read',       'List merchant and organizer accounts'),
    ('users:activation:write',     'Activate or deactivate a user account'),
    ('users:roles:write',          'Replace the role set on a user account'),
    ('users:mfa:reset',            'Reset a user''s MFA enrolment'),
    ('users:password:reset',       'Issue a temporary password for a user account'),
    ('roles:read',                 'List roles and the available permission catalog'),
    ('roles:write',                'Create, edit and delete custom roles'),
    ('service-requests:read',      'List submitted service-bundle requests'),
    ('service-requests:approve',   'Approve a service-bundle request'),
    ('team-members:read',          'Read an event organizer''s team members'),
    ('team-members:write',         'Create an event organizer team member'),
    ('team-members:manage',        'Enable, delete, reset or re-scope a team member'),
    ('shop-admins:write',          'Create a shop admin under a loyalty merchant'),
    ('shop-users:write',           'Create shop POS users, individually or by CSV upload'),
    ('shop-staff:read',            'Read the shop staff of your own shop or merchant'),
    ('shop-staff:merchant:read',   'Read shop staff across any shop of a merchant'),
    ('shop-staff:password:reset',  'Issue a temporary password for a shop staff account')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Seed: the nine built-in roles.
--
-- Descriptions are lifted from UpdateRolesDTO so the two do not drift.
-- ---------------------------------------------------------------------------
INSERT INTO roles (name, description, builtin) VALUES
    ('SUPER_ADMIN',     'Platform owner. Seeded once from BOOTSTRAP_ADMIN_PASSWORD and never grantable or revocable through the admin API.', TRUE),
    ('PRODUCT_OFFICER', 'Internal platform staff. Not scoped to a tenant, merchant or shop, and grants no service bundle.', TRUE),
    ('PRODUCT_MANAGER', 'Internal platform staff, same shape as PRODUCT_OFFICER.', TRUE),
    ('EVENT_ORGANIZER', 'Runs ticketed events; owns events, invoices and team members.', TRUE),
    ('TEAM_MEMBER',     'Gate staff / scanner operator working for one EVENT_ORGANIZER.', TRUE),
    ('MERCHANT_ADMIN',  'Runs a loyalty merchant; manages that merchant''s shops and rules.', TRUE),
    ('SHOP_ADMIN',      'Manages staff at one loyalty shop.', TRUE),
    ('SHOP_USER',       'Operates the POS at one loyalty shop.', TRUE),
    ('CUSTOMER',        'End user who earns and redeems loyalty points and buys tickets.', TRUE)
ON CONFLICT (name) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Seed: role -> permission, reproducing the CURRENT @PreAuthorize matrix
-- exactly. This migration must not change who can call what — it only restates
-- the existing rules in the new vocabulary, so the switch from hasRole to
-- hasAuthority is behaviour-neutral and any later change is a visible diff.
--
-- SUPER_ADMIN gets the '*' wildcard rather than an enumerated list ON PURPOSE.
-- Enumerating today's permissions would mean a permission added by a future
-- release is NOT held by SUPER_ADMIN, so the platform owner would silently lose
-- access to each new endpoint the moment it shipped — a lockout that presents
-- as a mysterious 403 and would be diagnosed as a bug in the new endpoint.
-- PermissionResolver expands '*' against the live catalog at token-mint time.
-- ---------------------------------------------------------------------------
INSERT INTO role_permissions (role_name, permission_code) VALUES
    ('SUPER_ADMIN', '*'),

    -- The ONLY endpoint these two currently reach: GET /admin/users/merchants.
    -- Deliberately not widened here; per User.Role's comment their remit is
    -- still undecided, and this migration is not the place to decide it.
    ('PRODUCT_OFFICER', 'users:merchants:read'),
    ('PRODUCT_MANAGER', 'users:merchants:read'),

    ('EVENT_ORGANIZER', 'team-members:read'),
    ('EVENT_ORGANIZER', 'team-members:write'),
    ('EVENT_ORGANIZER', 'team-members:manage'),

    ('MERCHANT_ADMIN', 'shop-admins:write'),
    ('MERCHANT_ADMIN', 'shop-staff:read'),
    ('MERCHANT_ADMIN', 'shop-staff:merchant:read'),
    ('MERCHANT_ADMIN', 'shop-staff:password:reset'),

    ('SHOP_ADMIN', 'shop-users:write'),
    ('SHOP_ADMIN', 'shop-staff:read'),
    ('SHOP_ADMIN', 'shop-staff:password:reset')

    -- TEAM_MEMBER, SHOP_USER and CUSTOMER intentionally get NOTHING here. They
    -- hold no user-service permission today: TEAM_MEMBER's power lives in
    -- booking-service (ticket scanning) and SHOP_USER's in loyalty-service, and
    -- neither has been migrated to permissions yet. An empty row set is the
    -- correct restatement of "no @PreAuthorize in this service names them".
ON CONFLICT (role_name, permission_code) DO NOTHING;
