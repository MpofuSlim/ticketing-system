-- Organizations: the BUSINESS a person works for, as a record of its own.
--
-- Until now a business had no identity of its own. A seller on the
-- marketplace was a loyalty merchant id minted onto the token at login by
-- asking loyalty-service, and an organizer was a user uuid. So a business that
-- only wanted the marketplace still had to exist in loyalty first, login
-- depended on loyalty being up, and a business could have exactly one person.
--
-- An organization is owned HERE, beside the users who belong to it. Each
-- product hangs its own record off the organization (marketplace's seller,
-- loyalty's merchants, ticketing's organizer) instead of borrowing another
-- product's id.
--
-- This migration is ADDITIVE. Ticketing is live and reads none of it; the
-- claims it adds to a token are new keys beside the existing ones.

CREATE TABLE organizations (
    id                   UUID          PRIMARY KEY,
    name                 VARCHAR(255)  NOT NULL,
    contact_email        VARCHAR(255),
    contact_phone        VARCHAR(255),
    address              VARCHAR(255),
    registration_number  VARCHAR(255),
    status               VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE'
                             CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_by_user_id   BIGINT        REFERENCES users(id) ON DELETE SET NULL,
    created_at           TIMESTAMP     NOT NULL,
    updated_at           TIMESTAMP
);

-- A person's place in an organization. OWNER / ADMIN / STAFF are roles INSIDE
-- one business, which is what lets the same person own one business and work
-- as staff in another. Platform-staff roles (SUPER_ADMIN etc.) stay global on
-- user_roles and are not memberships.
CREATE TABLE organization_members (
    id               UUID         PRIMARY KEY,
    organization_id  UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id          BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role             VARCHAR(16)  NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'STAFF')),
    created_at       TIMESTAMP    NOT NULL,
    CONSTRAINT uq_organization_member UNIQUE (organization_id, user_id)
);
CREATE INDEX idx_organization_members_user ON organization_members (user_id);

-- The products an organization has been GRANTED. Pending asks live where they
-- always have, in service_requests; this table holds only decisions. product
-- is the same vocabulary as users.default_services (Services.java), validated
-- in code rather than by a CHECK, exactly like service_requests.service.
CREATE TABLE organization_products (
    id               UUID         PRIMARY KEY,
    organization_id  UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    product          VARCHAR(32)  NOT NULL,
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP,
    CONSTRAINT uq_organization_product UNIQUE (organization_id, product)
);

-- The organization a session is acting for. It rides the refresh ROW for the
-- same reason phone_proof does (V38): /auth/refresh re-derives every claim
-- from the live user, so a choice kept anywhere else would evaporate on the
-- first rotation. No FK, deliberately: the refresh path re-checks membership
-- on every rotation, which is the check that matters, and a dangling id there
-- just means "choose again".
ALTER TABLE refresh_tokens ADD COLUMN organization_id UUID;

-- Which organization a product request was made FOR, so approving it grants
-- the product to that business. Null on every pre-V39 row; approval falls
-- back to the requester's own organization.
ALTER TABLE service_requests ADD COLUMN organization_id UUID;

-- ---------------------------------------------------------------------------
-- Backfill: one organization per business owner.
--
-- A business owner is anyone holding EVENT_ORGANIZER or MERCHANT_ADMIN: those
-- are the two roles the registration bundles grant (Services.BUNDLE_ROLES).
-- Staff (TEAM_MEMBER, SHOP_ADMIN, SHOP_USER) are NOT owners and get no
-- organization here; linking them to their employer's organization is a later
-- step. Someone holding both owner roles gets ONE organization with both
-- products, which is the point.
--
-- The name prefers the registered business name, then the person's name, so
-- no organization is ever nameless. Timestamps are UTC, like every other
-- zone-less column in this service.
-- ---------------------------------------------------------------------------

INSERT INTO organizations (id, name, contact_email, contact_phone, address,
                           registration_number, status, created_by_user_id, created_at)
SELECT gen_random_uuid(),
       COALESCE(NULLIF(TRIM(tp.business_name), ''),
                NULLIF(TRIM(CONCAT_WS(' ', u.first_name, u.last_name)), ''),
                u.email,
                u.phone_number),
       COALESCE(NULLIF(TRIM(tp.business_email), ''), u.email),
       COALESCE(NULLIF(TRIM(tp.business_phone_number), ''), u.phone_number),
       NULLIF(TRIM(tp.business_address), ''),
       NULLIF(TRIM(tp.registration_number), ''),
       'ACTIVE',
       u.id,
       (NOW() AT TIME ZONE 'UTC')
FROM users u
LEFT JOIN tenant_profiles tp ON tp.user_id = u.id
WHERE EXISTS (SELECT 1 FROM user_roles r
              WHERE r.user_id = u.id
                AND r.role IN ('EVENT_ORGANIZER', 'MERCHANT_ADMIN'));

INSERT INTO organization_members (id, organization_id, user_id, role, created_at)
SELECT gen_random_uuid(), o.id, o.created_by_user_id, 'OWNER', o.created_at
FROM organizations o
WHERE o.created_by_user_id IS NOT NULL;

-- Products mirror the bundles the owner already holds: the same access,
-- recorded against the business instead of the person.
INSERT INTO organization_products (id, organization_id, product, status, created_at)
SELECT gen_random_uuid(), o.id, LOWER(TRIM(s.service)), 'ACTIVE', o.created_at
FROM organizations o
JOIN user_default_services s ON s.user_id = o.created_by_user_id
WHERE LOWER(TRIM(s.service)) IN ('ticketing', 'loyalty', 'marketplace')
ON CONFLICT (organization_id, product) DO NOTHING;
