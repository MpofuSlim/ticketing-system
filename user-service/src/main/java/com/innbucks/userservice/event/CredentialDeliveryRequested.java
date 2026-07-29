package com.innbucks.userservice.event;

/**
 * Published by {@code UserAdminService} (SUPER_ADMIN approval / password reset)
 * and {@code ShopStaffService} (shop-staff onboarding / reset) after a
 * freshly-generated temporary password has been persisted. The listener
 * ({@code CredentialDeliveryListener}) hears it post-commit, off the HTTP
 * request thread, and runs the email / SMS / WhatsApp fallback chain.
 *
 * <p>Carries everything the listener needs so it never re-reads the user.
 * {@code tempPassword} is the plaintext value to deliver — it is NEVER logged
 * and never leaves the listener's outbound notification calls. {@code reason}
 * disambiguates the subject line and audit ref (APPROVAL-N vs PWRESET-N).
 */
public record CredentialDeliveryRequested(
        Long userId,
        String firstName,
        String email,
        String phoneNumber,
        String tempPassword,
        Reason reason,
        /**
         * The business/tenant this account belongs to, when it has one — used
         * to name it in the approval copy ("Your Fast Jet tenant account has
         * been approved"). Null for accounts with no tenant profile, and the
         * copy falls back to the generic wording.
         */
        String organisationName
) {
    /** Back-compat constructor for publishers that carry no organisation. */
    public CredentialDeliveryRequested(Long userId, String firstName, String email,
                                       String phoneNumber, String tempPassword, Reason reason) {
        this(userId, firstName, email, phoneNumber, tempPassword, reason, null);
    }

    public enum Reason {
        /** SUPER_ADMIN approved the account (first activation). */
        APPROVAL,
        /** SUPER_ADMIN reset the temporary password for an existing account. */
        RESET,
        /** A new shop-staff account was created and provisioned with credentials
         *  (MERCHANT_ADMIN creating a SHOP_ADMIN, or SHOP_ADMIN creating a
         *  SHOP_USER) — "welcome, your account is ready". */
        ONBOARDING
    }
}
