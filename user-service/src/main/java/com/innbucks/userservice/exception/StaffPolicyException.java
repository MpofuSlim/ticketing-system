package com.innbucks.userservice.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A refusal of an administrative action on an account, carrying a stable
 * {@code errorCode} the console branches on plus any extra detail (for example
 * {@code reason}). Rendered by {@link GlobalExceptionHandler} as
 * {@code {"code", "message", "data": {"errorCode": ..., <extra>}}}, the same
 * envelope as {@link OrganizationException}.
 *
 * <p>Introduced for {@code 403 target_not_manageable} on
 * {@code POST /admin/users/{id}/mfa/reset}; it is the typed refusal the staff
 * account work builds its error catalogue on, so the message is always a typed
 * constant written for a person and safe to pass through.
 */
@Getter
public class StaffPolicyException extends RuntimeException {

    /** {@code errorCode} for an account the caller may not act on. */
    public static final String TARGET_NOT_MANAGEABLE = "target_not_manageable";
    /** The person-facing message that goes with {@link #TARGET_NOT_MANAGEABLE}. */
    public static final String TARGET_NOT_MANAGEABLE_MESSAGE = "You can't change this account.";

    private final HttpStatus status;
    private final String errorCode;
    private final Map<String, Object> extra;
    /** Seconds until the refused action may be retried — sent as {@code Retry-After} on a 429; null otherwise. */
    private final Long retryAfterSeconds;

    public StaffPolicyException(HttpStatus status, String errorCode, String message, Map<String, ?> extra) {
        this(status, errorCode, message, extra, null);
    }

    public StaffPolicyException(HttpStatus status, String errorCode, String message, Map<String, ?> extra,
                                Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.extra = extra == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * 403 {@code target_not_manageable}. {@code reason} says why —
     * {@code super_admin}: the platform-owner account is never managed through
     * the admin API; {@code exceeds_your_authority}: the account holds a
     * permission the caller does not.
     */
    public static StaffPolicyException targetNotManageable(String reason) {
        return new StaffPolicyException(HttpStatus.FORBIDDEN, TARGET_NOT_MANAGEABLE,
                TARGET_NOT_MANAGEABLE_MESSAGE, Map.of("reason", reason));
    }

    /** {@code reason}: the target is the platform-owner account. */
    public static final String REASON_SUPER_ADMIN = "super_admin";
    /**
     * {@code reason}: the caller does not hold everything the thing they are
     * handing out (or the account they are acting on) holds.
     */
    public static final String REASON_EXCEEDS_YOUR_AUTHORITY = "exceeds_your_authority";
    /**
     * {@code reason}: a NAMED staff role the caller does not hold themselves
     * (on assignment; acting on a holder of one reports
     * {@link #REASON_EXCEEDS_YOUR_AUTHORITY}).
     */
    public static final String REASON_NAMED_ROLE_NOT_HELD = "named_role_not_held";
    /**
     * {@code reason}: a code only the wildcard may hold — or, for a role being
     * given to an account, a role that stores one (a legacy grant).
     */
    public static final String REASON_RESERVED_TO_SUPER_ADMIN = "reserved_to_super_admin";

    /** {@code errorCode} for a role the caller may not give to this account. */
    public static final String ROLE_NOT_ASSIGNABLE = "role_not_assignable";
    /** {@code errorCode} for a permission the caller may not grant to a role. */
    public static final String PERMISSION_NOT_ASSIGNABLE = "permission_not_assignable";

    /**
     * 400 {@code role_not_assignable}, {@code data.roles} = role name → reason,
     * in the order given. The message names each role with a readable reason so
     * the console can show it verbatim.
     */
    public static StaffPolicyException roleNotAssignable(Map<String, String> reasonsByRole) {
        return new StaffPolicyException(HttpStatus.BAD_REQUEST, ROLE_NOT_ASSIGNABLE,
                "These roles can't be given to this account: " + describe(reasonsByRole) + ".",
                Map.of("roles", new LinkedHashMap<>(reasonsByRole)));
    }

    /**
     * 400 {@code permission_not_assignable}, {@code data.codes} = permission code
     * → reason, in the order given.
     */
    public static StaffPolicyException permissionNotAssignable(Map<String, String> reasonsByCode) {
        return new StaffPolicyException(HttpStatus.BAD_REQUEST, PERMISSION_NOT_ASSIGNABLE,
                "These permissions can't be granted here: " + describe(reasonsByCode) + ".",
                Map.of("codes", new LinkedHashMap<>(reasonsByCode)));
    }

    // ---------------------------------------------------------------------
    // Staff accounts (V44). Messages are the exact text the console shows and
    // the Swagger examples quote; grep for the constant before changing one.
    // ---------------------------------------------------------------------

    public static final String REASON_RESERVED = "reserved";
    public static final String REASON_UNKNOWN = "unknown";
    public static final String REASON_NOT_A_STAFF_ROLE = "not_a_staff_role";

    public static final String STAFF_DOMAINS_UNCONFIGURED = "staff_domains_unconfigured";
    public static final String STAFF_INVITES_UNCONFIGURED = "staff_invites_unconfigured";
    public static final String UNCONFIGURED_MESSAGE = "Staff accounts aren't set up on this server yet.";
    public static final String STAFF_EMAIL_UNVERIFIED = "staff_email_unverified";
    public static final String STAFF_EMAIL_UNVERIFIED_MESSAGE =
            "This account's email has never been confirmed. Send them a staff invite first.";
    public static final String STAFF_HOLDERS_INELIGIBLE = "staff_holders_ineligible";
    public static final String STAFF_HOLDERS_INELIGIBLE_MESSAGE =
            "Some accounts holding this role haven't confirmed an InnBucks email. Invite or remove them first.";
    public static final String INVALID_REQUEST = "invalid_request";
    public static final String INVALID_PHONE_MESSAGE = "That isn't a valid mobile number.";
    public static final String CANNOT_DEACTIVATE_SELF = "cannot_deactivate_self";
    public static final String CANNOT_DEACTIVATE_SELF_MESSAGE = "You can't deactivate your own account.";
    public static final String ROLES_NOT_ACCEPTED = "roles_not_accepted";
    public static final String ROLES_NOT_ACCEPTED_MESSAGE =
            "Staff accounts are created by an administrator with POST /admin/staff.";
    public static final String EMAIL_DOMAIN_RESERVED = "email_domain_reserved";
    public static final String EMAIL_DOMAIN_RESERVED_MESSAGE =
            "InnBucks staff addresses can't be used here. Your administrator will invite you.";
    public static final String INVITE_INVALID = "invite_invalid";
    public static final String INVITE_INVALID_MESSAGE =
            "This invite link is no longer valid. Ask your administrator to send a new one.";
    public static final String STAFF_NOT_FOUND = "staff_not_found";
    public static final String STAFF_NOT_FOUND_MESSAGE = "Staff account not found.";
    public static final String EMAIL_TAKEN = "email_taken";
    public static final String EMAIL_TAKEN_MESSAGE = "An account with this email already exists.";
    public static final String ALREADY_DEACTIVATED = "already_deactivated";
    public static final String ALREADY_DEACTIVATED_MESSAGE =
            "This staff account is already deactivated. Reactivate it instead.";
    public static final String NOT_DEACTIVATED = "not_deactivated";
    public static final String NOT_DEACTIVATED_MESSAGE =
            "This staff account is not deactivated, so there is nothing to reactivate.";
    public static final String INVITE_NOT_PENDING = "invite_not_pending";
    public static final String INVITE_NOT_PENDING_MESSAGE =
            "This account has no pending invite: it has already set its password.";
    public static final String INVITE_NOT_PENDING_DEACTIVATED_MESSAGE =
            "This account is deactivated. Reactivate the account first, then send a new invite.";
    public static final String ADOPTION_BLOCKED = "adoption_blocked";
    public static final String ADOPTION_HOLDS_NON_STAFF_ROLES = "holds_non_staff_roles";
    public static final String ADOPTION_ORGANIZATION_MEMBER = "organization_member";
    public static final String ADOPTION_OFF_DOMAIN = "off_domain";
    public static final String STAFF_ACCOUNT_NOT_ELIGIBLE = "staff_account_not_eligible";
    public static final String STAFF_ACCOUNT_NOT_ELIGIBLE_MESSAGE =
            "InnBucks staff accounts can't join a business or request products.";
    public static final String USE_STAFF_INVITE = "use_staff_invite";
    public static final String USE_STAFF_INVITE_MESSAGE = "Staff set passwords through an invite.";
    public static final String USE_STAFF_ENDPOINTS = "use_staff_endpoints";
    public static final String USE_STAFF_ENDPOINTS_MESSAGE =
            "Reactivate staff with POST /admin/staff/{id}/reactivate.";
    public static final String INVITE_RESEND_LIMITED = "invite_resend_limited";
    public static final String STAFF_CREATE_LIMITED = "staff_create_limited";

    public static StaffPolicyException staffDomainsUnconfigured() {
        return new StaffPolicyException(HttpStatus.SERVICE_UNAVAILABLE, STAFF_DOMAINS_UNCONFIGURED,
                UNCONFIGURED_MESSAGE, Map.of());
    }

    public static StaffPolicyException staffInvitesUnconfigured() {
        return new StaffPolicyException(HttpStatus.SERVICE_UNAVAILABLE, STAFF_INVITES_UNCONFIGURED,
                UNCONFIGURED_MESSAGE, Map.of());
    }

    public static StaffPolicyException staffEmailUnverified() {
        return new StaffPolicyException(HttpStatus.BAD_REQUEST, STAFF_EMAIL_UNVERIFIED,
                STAFF_EMAIL_UNVERIFIED_MESSAGE, Map.of());
    }

    /**
     * 400 {@code staff_holders_ineligible}: {@code ineligibleHolders} (count),
     * {@code byReason} (reason → count) and {@code sample} (at most 20 userUuids).
     */
    public static StaffPolicyException staffHoldersIneligible(int count, Map<String, Integer> byReason,
                                                              java.util.List<String> sample) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("ineligibleHolders", count);
        extra.put("byReason", new LinkedHashMap<>(byReason));
        extra.put("sample", java.util.List.copyOf(sample));
        return new StaffPolicyException(HttpStatus.BAD_REQUEST, STAFF_HOLDERS_INELIGIBLE,
                STAFF_HOLDERS_INELIGIBLE_MESSAGE, extra);
    }

    public static StaffPolicyException invalidPhone() {
        return new StaffPolicyException(HttpStatus.BAD_REQUEST, INVALID_REQUEST, INVALID_PHONE_MESSAGE,
                Map.of("field", "phoneNumber"));
    }

    public static StaffPolicyException cannotDeactivateSelf() {
        return new StaffPolicyException(HttpStatus.BAD_REQUEST, CANNOT_DEACTIVATE_SELF,
                CANNOT_DEACTIVATE_SELF_MESSAGE, Map.of());
    }

    public static StaffPolicyException rolesNotAccepted() {
        return new StaffPolicyException(HttpStatus.BAD_REQUEST, ROLES_NOT_ACCEPTED, ROLES_NOT_ACCEPTED_MESSAGE,
                Map.of("field", "roles"));
    }

    public static StaffPolicyException emailDomainReserved() {
        return new StaffPolicyException(HttpStatus.BAD_REQUEST, EMAIL_DOMAIN_RESERVED,
                EMAIL_DOMAIN_RESERVED_MESSAGE, Map.of("field", "email"));
    }

    public static StaffPolicyException inviteInvalid() {
        return new StaffPolicyException(HttpStatus.BAD_REQUEST, INVITE_INVALID, INVITE_INVALID_MESSAGE, Map.of());
    }

    public static StaffPolicyException staffNotFound() {
        return new StaffPolicyException(HttpStatus.NOT_FOUND, STAFF_NOT_FOUND, STAFF_NOT_FOUND_MESSAGE, Map.of());
    }

    public static StaffPolicyException emailTaken() {
        return new StaffPolicyException(HttpStatus.CONFLICT, EMAIL_TAKEN, EMAIL_TAKEN_MESSAGE,
                Map.of("field", "email"));
    }

    public static StaffPolicyException alreadyDeactivated() {
        return new StaffPolicyException(HttpStatus.CONFLICT, ALREADY_DEACTIVATED, ALREADY_DEACTIVATED_MESSAGE,
                Map.of());
    }

    public static StaffPolicyException notDeactivated() {
        return new StaffPolicyException(HttpStatus.CONFLICT, NOT_DEACTIVATED, NOT_DEACTIVATED_MESSAGE, Map.of());
    }

    public static StaffPolicyException inviteNotPending(boolean deactivated) {
        return new StaffPolicyException(HttpStatus.CONFLICT, INVITE_NOT_PENDING,
                deactivated ? INVITE_NOT_PENDING_DEACTIVATED_MESSAGE : INVITE_NOT_PENDING_MESSAGE, Map.of());
    }

    /** 409 {@code adoption_blocked}, {@code data.reason}, one sentence per reason. */
    public static StaffPolicyException adoptionBlocked(String reason) {
        return new StaffPolicyException(HttpStatus.CONFLICT, ADOPTION_BLOCKED, adoptionBlockedMessage(reason),
                Map.of("reason", reason));
    }

    public static String adoptionBlockedMessage(String reason) {
        return switch (reason) {
            case ADOPTION_HOLDS_NON_STAFF_ROLES -> "This account can't be adopted yet: it also holds business roles. "
                    + "Remove them with PUT /admin/users/{id}/roles first.";
            case ADOPTION_ORGANIZATION_MEMBER -> "This account can't be adopted yet: it still belongs to a business "
                    + "organization. Suspend that organization first.";
            case ADOPTION_OFF_DOMAIN -> "This account can't be adopted: its email is not an InnBucks staff address. "
                    + "Remove its staff roles and invite the person at their InnBucks address.";
            default -> "This account can't be adopted yet.";
        };
    }

    public static StaffPolicyException staffAccountNotEligible() {
        return new StaffPolicyException(HttpStatus.CONFLICT, STAFF_ACCOUNT_NOT_ELIGIBLE,
                STAFF_ACCOUNT_NOT_ELIGIBLE_MESSAGE, Map.of());
    }

    public static StaffPolicyException useStaffInvite() {
        return new StaffPolicyException(HttpStatus.CONFLICT, USE_STAFF_INVITE, USE_STAFF_INVITE_MESSAGE, Map.of());
    }

    public static StaffPolicyException useStaffEndpoints() {
        return new StaffPolicyException(HttpStatus.CONFLICT, USE_STAFF_ENDPOINTS, USE_STAFF_ENDPOINTS_MESSAGE,
                Map.of());
    }

    /** 429 {@code invite_resend_limited} with {@code Retry-After} and {@code retryAfterSeconds}. */
    public static StaffPolicyException inviteResendLimited(long retryAfterSeconds, int limit) {
        long seconds = Math.max(1, retryAfterSeconds);
        return new StaffPolicyException(HttpStatus.TOO_MANY_REQUESTS, INVITE_RESEND_LIMITED,
                "Too many invites for this account: " + limit + " a day is the limit. Try again in "
                        + humanDuration(seconds) + ".",
                Map.of("retryAfterSeconds", seconds), seconds);
    }

    /** 429 {@code staff_create_limited} with {@code Retry-After} and {@code retryAfterSeconds}. */
    public static StaffPolicyException staffCreateLimited(long retryAfterSeconds, int limit) {
        long seconds = Math.max(1, retryAfterSeconds);
        return new StaffPolicyException(HttpStatus.TOO_MANY_REQUESTS, STAFF_CREATE_LIMITED,
                "Too many staff accounts created: " + limit + " a day is the limit. Try again in "
                        + humanDuration(seconds) + ".",
                Map.of("retryAfterSeconds", seconds), seconds);
    }

    /** "3 hours", "12 minutes", "1 minute" — rounded UP, so the wait is never understated. */
    static String humanDuration(long seconds) {
        if (seconds >= 3600) {
            long hours = (seconds + 3599) / 3600;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        long minutes = Math.max(1, (seconds + 59) / 60);
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    private static String describe(Map<String, String> reasons) {
        StringBuilder text = new StringBuilder();
        reasons.forEach((name, reason) -> {
            if (!text.isEmpty()) text.append(", ");
            text.append(name).append(" (").append(readable(reason)).append(')');
        });
        return text.toString();
    }

    private static String readable(String reason) {
        return switch (reason) {
            case REASON_RESERVED_TO_SUPER_ADMIN -> "reserved to SUPER_ADMIN";
            case REASON_EXCEEDS_YOUR_AUTHORITY -> "grants more than you hold";
            case REASON_NAMED_ROLE_NOT_HELD -> "only someone who holds this role can give it";
            default -> reason.replace('_', ' ');
        };
    }
}
