-- Backfill users.approved so it is honest for the new login-time approval gate.
--
-- Context: the `approved` flag (V17) is only meaningful for system users
-- created via /auth/register + SUPER_ADMIN approval. Customers (OtpService),
-- team members (TeamMemberService) and shop staff (ShopStaffService) are
-- auto-approved at creation, but earlier code left their `approved` flag at the
-- column default (FALSE). Login now returns a distinct "account pending
-- approval" (403) response for accounts that are BOTH inactive AND unapproved,
-- so every legitimately-usable account must read as approved, and no
-- auto-approved role must ever look "pending".

-- 1) Every currently-active account is, by definition, already approved.
UPDATE users SET approved = TRUE WHERE active = TRUE AND approved = FALSE;

-- 2) Roles that never go through the SUPER_ADMIN approval flow are approved
--    regardless of active state, so a deactivated customer / team member /
--    shop-staff row is never mistaken for a pending registration.
UPDATE users SET approved = TRUE
 WHERE approved = FALSE
   AND id IN (SELECT user_id FROM user_roles
              WHERE role IN ('CUSTOMER', 'TEAM_MEMBER', 'SHOP_ADMIN', 'SHOP_USER'));
