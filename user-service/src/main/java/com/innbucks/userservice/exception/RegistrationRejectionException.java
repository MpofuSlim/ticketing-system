package com.innbucks.userservice.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * A refusal of {@code PUT /admin/users/{id}/reject}: the registration cannot be
 * rejected, or its rejection could not be checked. Every one carries a stable
 * {@code errorCode} the console branches on; a business that is in use also
 * says WHY in {@code data.reason}.
 *
 * <p>A {@link StaffPolicyException} so it renders through the same handler and
 * envelope as the other refusals of an administrative action on an account
 * ({@code {"code", "message", "data": {"errorCode", ...}}}). Messages are typed
 * constants written for a person and quoted by the endpoint's Swagger examples;
 * grep for the constant before changing one.
 */
public class RegistrationRejectionException extends StaffPolicyException {

    /** 409: the account was approved; a decided registration is deactivated, not rejected. */
    public static final String ALREADY_DECIDED = "registration_already_decided";
    public static final String ALREADY_DECIDED_MESSAGE =
            "This account has already been approved. Deactivate it instead of rejecting it.";

    /** 409: something else depends on the account or its business; {@code data.reason} says what. */
    public static final String BUSINESS_IN_USE = "registration_business_in_use";

    /** {@code reason}: the business the registration created has members besides the applicant. */
    public static final String REASON_OTHER_MEMBERS = "other_members";
    public static final String OTHER_MEMBERS_MESSAGE = "This business already has other members, so the "
            + "registration can't be rejected. Approve it, or remove the other members first.";

    /** {@code reason}: InnRewards holds a loyalty merchant for the business. */
    public static final String REASON_LOYALTY_MERCHANT = "loyalty_merchant";
    public static final String LOYALTY_MERCHANT_MESSAGE = "This business already has a loyalty merchant in "
            + "InnRewards. Remove it there first, or approve the registration.";

    /**
     * {@code reason}: accounts name this one as their organizer
     * ({@code users.created_by_organizer_uuid}, {@code ON DELETE RESTRICT}) —
     * team members are switched off, never deleted, so the account must stay.
     */
    public static final String REASON_TEAM_MEMBERS = "team_members";
    public static final String TEAM_MEMBERS_MESSAGE =
            "This account already has team members of its own, so the registration can't be rejected.";

    /** {@code reason}: the account also holds a super-app customer profile, which a rejection must not delete. */
    public static final String REASON_CUSTOMER_ACCOUNT = "customer_account";
    public static final String CUSTOMER_ACCOUNT_MESSAGE =
            "This account is also an InnBucks app customer, so the registration can't be rejected.";

    /** {@code reason}: removing the account would leave another business without an owner. */
    public static final String REASON_SOLE_OWNER_ELSEWHERE = "sole_owner_elsewhere";
    public static final String SOLE_OWNER_ELSEWHERE_MESSAGE = "This account is the only owner of another "
            + "business, so the registration can't be rejected. Make someone else an owner of that business first.";

    /** 503: InnRewards could not say whether it holds a merchant for the business. */
    public static final String CHECK_UNAVAILABLE = "registration_check_unavailable";
    public static final String CHECK_UNAVAILABLE_MESSAGE =
            "We couldn't confirm this business isn't already set up in InnRewards. Try again in a minute.";

    /** 409: what was checked before the InnRewards lookup no longer holds under the account's row lock. */
    public static final String CHANGED = "registration_changed";
    public static final String CHANGED_MESSAGE =
            "This registration changed while it was being rejected. Refresh and try again.";

    private RegistrationRejectionException(HttpStatus status, String errorCode, String message,
                                           Map<String, ?> extra) {
        super(status, errorCode, message, extra);
    }

    public static RegistrationRejectionException alreadyDecided() {
        return new RegistrationRejectionException(HttpStatus.CONFLICT, ALREADY_DECIDED, ALREADY_DECIDED_MESSAGE,
                Map.of());
    }

    /** 409 {@code registration_business_in_use} with {@code data.reason} and its sentence. */
    public static RegistrationRejectionException businessInUse(String reason) {
        return new RegistrationRejectionException(HttpStatus.CONFLICT, BUSINESS_IN_USE, businessInUseMessage(reason),
                Map.of("reason", reason));
    }

    private static String businessInUseMessage(String reason) {
        return switch (reason) {
            case REASON_OTHER_MEMBERS -> OTHER_MEMBERS_MESSAGE;
            case REASON_LOYALTY_MERCHANT -> LOYALTY_MERCHANT_MESSAGE;
            case REASON_TEAM_MEMBERS -> TEAM_MEMBERS_MESSAGE;
            case REASON_CUSTOMER_ACCOUNT -> CUSTOMER_ACCOUNT_MESSAGE;
            case REASON_SOLE_OWNER_ELSEWHERE -> SOLE_OWNER_ELSEWHERE_MESSAGE;
            default -> throw new IllegalArgumentException("Unknown registration_business_in_use reason: " + reason);
        };
    }

    public static RegistrationRejectionException checkUnavailable() {
        return new RegistrationRejectionException(HttpStatus.SERVICE_UNAVAILABLE, CHECK_UNAVAILABLE,
                CHECK_UNAVAILABLE_MESSAGE, Map.of());
    }

    public static RegistrationRejectionException changed() {
        return new RegistrationRejectionException(HttpStatus.CONFLICT, CHANGED, CHANGED_MESSAGE, Map.of());
    }
}
