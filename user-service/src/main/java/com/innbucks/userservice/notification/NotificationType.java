package com.innbucks.userservice.notification;

/**
 * The `type` values a client may receive, so the console can map icon, wording
 * and severity without guessing.
 *
 * <p><b>Deliberately a constants holder, not an enum.</b> The column is a
 * VARCHAR and the S2S ingress accepts any string: producers live in other
 * services and repos, so binding the column to an enum here would mean a
 * marketplace deploy could emit a type user-service refuses to store — the
 * notification would be lost at the moment it mattered. Unknown types are
 * stored and served; a client that does not recognise one renders it with the
 * default icon rather than failing.
 *
 * <p>These are the names promised to the console for §1.4. Everything not yet
 * listed as WIRED has no producer today — the resource is ready for it and
 * adding one is a single call to the ingress, not new plumbing.
 */
public final class NotificationType {

    private NotificationType() {}

    // --- Service requests (WIRED) -------------------------------------
    public static final String SERVICE_REQUEST_SUBMITTED = "SERVICE_REQUEST_SUBMITTED";
    public static final String SERVICE_REQUEST_APPROVED = "SERVICE_REQUEST_APPROVED";
    public static final String SERVICE_REQUEST_REJECTED = "SERVICE_REQUEST_REJECTED";

    // --- Events (ticketing) — not yet wired ---------------------------
    public static final String EVENT_SUBMITTED = "EVENT_SUBMITTED";
    public static final String EVENT_APPROVED = "EVENT_APPROVED";
    public static final String EVENT_REJECTED = "EVENT_REJECTED";
    public static final String EVENT_DEACTIVATED = "EVENT_DEACTIVATED";

    // --- Marketplace — not yet wired ----------------------------------
    public static final String LISTING_REPORTED = "LISTING_REPORTED";
    public static final String LISTING_REPORT_ACTIONED = "LISTING_REPORT_ACTIONED";
    public static final String SELLER_APPLIED = "SELLER_APPLIED";
    public static final String SELLER_APPROVED = "SELLER_APPROVED";
    public static final String SELLER_REJECTED = "SELLER_REJECTED";
    public static final String ORDER_PAID = "ORDER_PAID";
    public static final String ORDER_EXPIRED = "ORDER_EXPIRED";

    // --- Loyalty — not yet wired --------------------------------------
    public static final String FRAUD_ATTEMPT_DETECTED = "FRAUD_ATTEMPT_DETECTED";
    public static final String VOUCHER_BATCH_EXPIRING = "VOUCHER_BATCH_EXPIRING";
    public static final String INVOICE_ISSUED = "INVOICE_ISSUED";
    public static final String INVOICE_OVERDUE = "INVOICE_OVERDUE";

    // --- Payments — not yet wired -------------------------------------
    public static final String PAYMENT_CONFIRMED = "PAYMENT_CONFIRMED";
    public static final String PAYMENT_EXPIRED = "PAYMENT_EXPIRED";
    public static final String PAYOUT_FAILED = "PAYOUT_FAILED";

    // --- Account — not yet wired --------------------------------------
    public static final String ROLE_CHANGED = "ROLE_CHANGED";
    public static final String MFA_RESET_BY_ADMIN = "MFA_RESET_BY_ADMIN";
    public static final String ACCOUNT_DEACTIVATED = "ACCOUNT_DEACTIVATED";

    /** Fallback for a notification arriving with no type at all. */
    public static final String GENERAL = "GENERAL";
}
