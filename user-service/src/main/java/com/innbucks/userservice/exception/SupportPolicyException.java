package com.innbucks.userservice.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A refusal on the customer-support surface ({@code /admin/support/**}, and the
 * limiter on {@code GET /admin/device-security/**}). Rendered by
 * {@link GlobalExceptionHandler} as {@code {"code", "message", "data":
 * {"errorCode": ..., <extra>}}} — the {@link StaffPolicyException} envelope.
 *
 * <p>A separate type rather than more {@link StaffPolicyException} constants
 * because its handler must never log {@code extra}: a support refusal sits
 * next to customer data, and the rule on this surface is that a customer key
 * reaches a log only masked. The handler logs the status and errorCode, nothing
 * else. Every message is a typed constant written for an agent to read — the
 * Swagger examples quote them, so grep for the constant before changing one.
 */
@Getter
public class SupportPolicyException extends RuntimeException {

    // ---- the query ------------------------------------------------------------------------
    public static final String QUERY_NOT_ACCEPTED = "query_not_accepted";
    public static final String QUERY_NOT_ACCEPTED_MESSAGE =
            "Card, voucher and collection codes can't be searched. Ask the caller for their phone number or email, "
                    + "and type a phone number with its country code, like +263771234567.";
    public static final String QUERY_NOT_RECOGNISED = "query_not_recognised";
    public static final String QUERY_NOT_RECOGNISED_MESSAGE =
            "Search by the customer's phone number, email address or a support reference such as SEC-8F2KQ7.";
    public static final String QUERY_NOT_SUPPORTED = "query_not_supported";
    public static final String QUERY_NOT_SUPPORTED_MESSAGE = "Searching by this kind of reference isn't available yet.";
    public static final String QUERY_NOT_PERMITTED = "query_not_permitted";
    public static final String QUERY_NOT_PERMITTED_MESSAGE = "You can't search by this kind of reference.";

    // ---- lookup binding -------------------------------------------------------------------
    public static final String LOOKUP_REQUIRED = "lookup_required";
    public static final String LOOKUP_REQUIRED_MESSAGE =
            "Search for the customer first, then send the lookupId that search returned.";
    public static final String LOOKUP_EXPIRED = "lookup_expired";
    public static final String LOOKUP_EXPIRED_MESSAGE = "Search for the customer again.";
    public static final String TARGET_NOT_FOUND = "target_not_found";
    public static final String TARGET_NOT_FOUND_MESSAGE =
            "That account isn't part of this search. Search for the customer again.";

    // ---- who the action is aimed at -------------------------------------------------------
    public static final String SELF_ACTION = "support_self_action";
    public static final String SELF_ACTION_MESSAGE = "You can't act on your own account. Ask a colleague.";
    public static final String STAFF_TARGET_REQUIRES_SUPERVISOR = "staff_target_requires_supervisor";
    public static final String STAFF_TARGET_REQUIRES_SUPERVISOR_MESSAGE =
            "This search matches an InnBucks staff account. Ask a supervisor.";
    public static final String CONSOLE_STAFF_ACCOUNT = "console_staff_account";
    public static final String CONSOLE_STAFF_ACCOUNT_MESSAGE = "This is an InnBucks staff account; ask a SUPER_ADMIN.";
    public static final String AGENT_NOT_RESOLVED = "agent_not_resolved";
    public static final String AGENT_NOT_RESOLVED_MESSAGE = "Your account couldn't be verified. Sign in again.";

    // ---- idempotency ----------------------------------------------------------------------
    public static final String IDEMPOTENCY_KEY_REQUIRED = "idempotency_key_required";
    public static final String IDEMPOTENCY_KEY_REQUIRED_MESSAGE =
            "Send an Idempotency-Key header holding a new UUID for each action.";
    public static final String IDEMPOTENCY_KEY_REUSED = "idempotency_key_reused";
    public static final String IDEMPOTENCY_KEY_REUSED_MESSAGE =
            "This Idempotency-Key was already used for a different action. Send a new one.";

    // ---- the console actions --------------------------------------------------------------
    public static final String ACCOUNT_INACTIVE = "account_inactive";
    public static final String ACCOUNT_INACTIVE_MESSAGE =
            "This account isn't active, so support can't change it. An administrator decides whether to approve or "
                    + "reactivate it.";
    public static final String NO_EMAIL_ON_ACCOUNT = "no_email_on_account";
    public static final String NO_EMAIL_ON_ACCOUNT_MESSAGE =
            "This account has no email address, so a reset code can't be sent.";
    public static final String MFA_NOT_ENROLLED = "mfa_not_enrolled";
    public static final String MFA_NOT_ENROLLED_MESSAGE =
            "This account hasn't set up two-factor sign-in, so there is nothing to reset.";
    public static final String RESET_CODE_LIMITED = "reset_code_limited";
    public static final String RESET_CODE_LIMITED_MESSAGE =
            "Too many reset codes have gone to this account recently. Ask the caller to use the latest one, "
                    + "or try again later.";
    public static final String NOTE_REQUIRED = "note_required";
    public static final String NOTE_REQUIRED_MESSAGE =
            "Write a note saying why, for example how you verified the caller. Markup on its own doesn't count.";

    // ---- the surface ----------------------------------------------------------------------
    public static final String LOOKUP_RATE_LIMITED = "lookup_rate_limited";
    public static final String SUPPORT_DISABLED = "support_disabled";
    public static final String SUPPORT_DISABLED_MESSAGE = "Customer support isn't available on this server.";
    public static final String SUPPORT_LOG_UNAVAILABLE = "support_log_unavailable";
    public static final String SUPPORT_LOG_UNAVAILABLE_MESSAGE =
            "We couldn't record this lookup, so it wasn't shown. Try again.";
    public static final String SEARCH_UNAVAILABLE = "support_search_unavailable";
    public static final String SEARCH_UNAVAILABLE_MESSAGE =
            "We couldn't search the customer records just now. Try again in a minute.";

    private final HttpStatus status;
    private final String errorCode;
    private final Map<String, Object> extra;
    /** Seconds until the refused call may be retried — sent as {@code Retry-After} on a 429; null otherwise. */
    private final Long retryAfterSeconds;

    public SupportPolicyException(HttpStatus status, String errorCode, String message, Map<String, ?> extra,
                                  Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.extra = extra == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public SupportPolicyException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, Map.of(), null);
    }

    public static SupportPolicyException queryNotAccepted() {
        return new SupportPolicyException(HttpStatus.BAD_REQUEST, QUERY_NOT_ACCEPTED, QUERY_NOT_ACCEPTED_MESSAGE);
    }

    public static SupportPolicyException queryNotRecognised() {
        return new SupportPolicyException(HttpStatus.BAD_REQUEST, QUERY_NOT_RECOGNISED, QUERY_NOT_RECOGNISED_MESSAGE);
    }

    public static SupportPolicyException queryNotSupported() {
        return new SupportPolicyException(HttpStatus.BAD_REQUEST, QUERY_NOT_SUPPORTED, QUERY_NOT_SUPPORTED_MESSAGE);
    }

    public static SupportPolicyException queryNotPermitted() {
        return new SupportPolicyException(HttpStatus.FORBIDDEN, QUERY_NOT_PERMITTED, QUERY_NOT_PERMITTED_MESSAGE);
    }

    public static SupportPolicyException lookupRequired() {
        return new SupportPolicyException(HttpStatus.BAD_REQUEST, LOOKUP_REQUIRED, LOOKUP_REQUIRED_MESSAGE);
    }

    /** 409 {@code lookup_expired}: stale, someone else's, or unknown — deliberately indistinguishable. */
    public static SupportPolicyException lookupExpired() {
        return new SupportPolicyException(HttpStatus.CONFLICT, LOOKUP_EXPIRED, LOOKUP_EXPIRED_MESSAGE);
    }

    /**
     * 409 {@code lookup_expired} with {@code reason: section_not_in_lookup}: the
     * agent's own, fresh lookup did not return the section this call is for (a
     * reference search returns only the owning section). Safe to say: the
     * lookup is theirs.
     */
    public static SupportPolicyException lookupMissingSection() {
        return new SupportPolicyException(HttpStatus.CONFLICT, LOOKUP_EXPIRED, LOOKUP_EXPIRED_MESSAGE,
                Map.of("reason", "section_not_in_lookup"), null);
    }

    public static SupportPolicyException targetNotFound() {
        return new SupportPolicyException(HttpStatus.NOT_FOUND, TARGET_NOT_FOUND, TARGET_NOT_FOUND_MESSAGE);
    }

    public static SupportPolicyException selfAction() {
        return new SupportPolicyException(HttpStatus.FORBIDDEN, SELF_ACTION, SELF_ACTION_MESSAGE);
    }

    public static SupportPolicyException staffTargetRequiresSupervisor() {
        return new SupportPolicyException(HttpStatus.FORBIDDEN, STAFF_TARGET_REQUIRES_SUPERVISOR,
                STAFF_TARGET_REQUIRES_SUPERVISOR_MESSAGE);
    }

    public static SupportPolicyException consoleStaffAccount() {
        return new SupportPolicyException(HttpStatus.FORBIDDEN, CONSOLE_STAFF_ACCOUNT, CONSOLE_STAFF_ACCOUNT_MESSAGE);
    }

    public static SupportPolicyException agentNotResolved() {
        return new SupportPolicyException(HttpStatus.FORBIDDEN, AGENT_NOT_RESOLVED, AGENT_NOT_RESOLVED_MESSAGE);
    }

    public static SupportPolicyException idempotencyKeyRequired() {
        return new SupportPolicyException(HttpStatus.BAD_REQUEST, IDEMPOTENCY_KEY_REQUIRED,
                IDEMPOTENCY_KEY_REQUIRED_MESSAGE, Map.of("field", "Idempotency-Key"), null);
    }

    public static SupportPolicyException idempotencyKeyReused() {
        return new SupportPolicyException(HttpStatus.CONFLICT, IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_KEY_REUSED_MESSAGE);
    }

    public static SupportPolicyException accountInactive() {
        return new SupportPolicyException(HttpStatus.CONFLICT, ACCOUNT_INACTIVE, ACCOUNT_INACTIVE_MESSAGE);
    }

    public static SupportPolicyException noEmailOnAccount() {
        return new SupportPolicyException(HttpStatus.CONFLICT, NO_EMAIL_ON_ACCOUNT, NO_EMAIL_ON_ACCOUNT_MESSAGE);
    }

    public static SupportPolicyException mfaNotEnrolled() {
        return new SupportPolicyException(HttpStatus.CONFLICT, MFA_NOT_ENROLLED, MFA_NOT_ENROLLED_MESSAGE);
    }

    public static SupportPolicyException resetCodeLimited() {
        return new SupportPolicyException(HttpStatus.TOO_MANY_REQUESTS, RESET_CODE_LIMITED, RESET_CODE_LIMITED_MESSAGE);
    }

    /**
     * 400 {@code note_required}: the note passed {@code @NotBlank} but nothing is
     * left once markup and invisible characters are stripped — a seal must never
     * carry an empty "why".
     */
    public static SupportPolicyException noteRequired() {
        return new SupportPolicyException(HttpStatus.BAD_REQUEST, NOTE_REQUIRED, NOTE_REQUIRED_MESSAGE,
                Map.of("field", "note"), null);
    }

    public static SupportPolicyException supportDisabled() {
        return new SupportPolicyException(HttpStatus.NOT_FOUND, SUPPORT_DISABLED, SUPPORT_DISABLED_MESSAGE);
    }

    /**
     * 503 {@code support_search_unavailable}: the customer records could not be
     * read at all (resolving the query, or the staff check every lookup needs),
     * so there is no section to show as UNAVAILABLE.
     */
    public static SupportPolicyException searchUnavailable() {
        return new SupportPolicyException(HttpStatus.SERVICE_UNAVAILABLE, SEARCH_UNAVAILABLE, SEARCH_UNAVAILABLE_MESSAGE);
    }

    public static SupportPolicyException logUnavailable() {
        return new SupportPolicyException(HttpStatus.SERVICE_UNAVAILABLE, SUPPORT_LOG_UNAVAILABLE,
                SUPPORT_LOG_UNAVAILABLE_MESSAGE);
    }

    /**
     * 429 {@code lookup_rate_limited} with {@code Retry-After} and
     * {@code data.retryAfterSeconds}; {@code window} is {@code 10m} or {@code day}.
     */
    public static SupportPolicyException rateLimited(long retryAfterSeconds, String window) {
        long seconds = Math.max(1, retryAfterSeconds);
        String message = "day".equals(window)
                ? "You've reached today's limit for customer lookups. Try again in "
                        + StaffPolicyException.humanDurationPublic(seconds) + ", or ask a supervisor."
                : "You've looked up a lot of customers in a short time. Try again in "
                        + StaffPolicyException.humanDurationPublic(seconds) + ".";
        return new SupportPolicyException(HttpStatus.TOO_MANY_REQUESTS, LOOKUP_RATE_LIMITED, message,
                Map.of("retryAfterSeconds", seconds, "window", window), seconds);
    }
}
