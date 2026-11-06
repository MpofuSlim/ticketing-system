package com.innbucks.userservice.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * A refusal on the sales-agent portal invite surface (V56): the internal
 * {@code /users/internal/sales-agent-invites} calls and the public
 * {@code /auth/sales-agent-invite/**} ones. A {@link StaffPolicyException}, so
 * {@code GlobalExceptionHandler} renders it the same way:
 * {@code {"code", "message", "data": {"errorCode", ...}}}. Messages are typed
 * constants written for the person reading them.
 */
public class SalesAgentInviteException extends StaffPolicyException {

    // ---- internal (event-service → user-service) ------------------------

    public static final String INVALID_EMAIL = "invalid_email";
    public static final String INVALID_EMAIL_MESSAGE = "A valid email address is required.";
    public static final String INVALID_PHONE = "invalid_phone";
    public static final String INVALID_PHONE_MESSAGE = "A valid phoneNumber is required.";
    public static final String INVALID_REQUEST = "invalid_request";
    public static final String INVALID_REQUEST_MESSAGE = "teamMemberId and organizerUuid must be UUIDs.";
    public static final String UNCONFIGURED = "sales_agent_invites_unconfigured";
    public static final String UNCONFIGURED_MESSAGE =
            "Portal invites can't be sent from this cell right now. Try again later.";

    // ---- public (the invite page) ----------------------------------------

    /**
     * The staff invite's code ({@link StaffPolicyException#INVITE_INVALID}) with
     * the organiser named in the words: one opaque answer for an unknown, used,
     * revoked or expired link.
     */
    public static final String AGENT_INVITE_INVALID_MESSAGE =
            "This invite link is no longer valid. Ask the organiser to send a new one.";
    public static final String PHONE_CODE_INVALID = "phone_code_invalid";
    public static final String PHONE_CODE_INVALID_MESSAGE = "That code is wrong or has expired. Ask for a new one.";
    public static final String ACCOUNT_NOT_ELIGIBLE = "account_not_eligible";
    public static final String ACCOUNT_NOT_ELIGIBLE_MESSAGE = "The InnBucks account on this phone number can't be "
            + "used to sell tickets on the portal. Contact InnBucks support.";
    public static final String EMAIL_MISMATCH = "email_mismatch";
    public static final String EMAIL_MISMATCH_MESSAGE = "The InnBucks account on this phone number already uses a "
            + "different email address. Ask the organiser to send the invite to that address instead.";
    public static final String EMAIL_IN_USE = "email_in_use";
    public static final String EMAIL_IN_USE_MESSAGE = "This email address already belongs to another InnBucks "
            + "account. Ask the organiser to send the invite to a different address.";
    public static final String TRY_AGAIN = "try_again";
    public static final String TRY_AGAIN_MESSAGE =
            "Something changed while we were setting up your sign-in. Ask for a new code and try again.";

    public SalesAgentInviteException(HttpStatus status, String errorCode, String message) {
        super(status, errorCode, message, Map.of());
    }

    public static SalesAgentInviteException invalidEmail() {
        return new SalesAgentInviteException(HttpStatus.BAD_REQUEST, INVALID_EMAIL, INVALID_EMAIL_MESSAGE);
    }

    public static SalesAgentInviteException invalidPhone() {
        return new SalesAgentInviteException(HttpStatus.BAD_REQUEST, INVALID_PHONE, INVALID_PHONE_MESSAGE);
    }

    public static SalesAgentInviteException invalidRequest() {
        return new SalesAgentInviteException(HttpStatus.BAD_REQUEST, INVALID_REQUEST, INVALID_REQUEST_MESSAGE);
    }

    public static SalesAgentInviteException unconfigured() {
        return new SalesAgentInviteException(HttpStatus.SERVICE_UNAVAILABLE, UNCONFIGURED, UNCONFIGURED_MESSAGE);
    }

    public static SalesAgentInviteException inviteInvalid() {
        return new SalesAgentInviteException(HttpStatus.BAD_REQUEST, INVITE_INVALID, AGENT_INVITE_INVALID_MESSAGE);
    }

    public static SalesAgentInviteException phoneCodeInvalid() {
        return new SalesAgentInviteException(HttpStatus.BAD_REQUEST, PHONE_CODE_INVALID, PHONE_CODE_INVALID_MESSAGE);
    }

    public static SalesAgentInviteException accountNotEligible() {
        return new SalesAgentInviteException(HttpStatus.CONFLICT, ACCOUNT_NOT_ELIGIBLE, ACCOUNT_NOT_ELIGIBLE_MESSAGE);
    }

    public static SalesAgentInviteException emailMismatch() {
        return new SalesAgentInviteException(HttpStatus.CONFLICT, EMAIL_MISMATCH, EMAIL_MISMATCH_MESSAGE);
    }

    public static SalesAgentInviteException emailInUse() {
        return new SalesAgentInviteException(HttpStatus.CONFLICT, EMAIL_IN_USE, EMAIL_IN_USE_MESSAGE);
    }

    public static SalesAgentInviteException tryAgain() {
        return new SalesAgentInviteException(HttpStatus.CONFLICT, TRY_AGAIN, TRY_AGAIN_MESSAGE);
    }
}
