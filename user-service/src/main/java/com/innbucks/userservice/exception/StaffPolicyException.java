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

    public StaffPolicyException(HttpStatus status, String errorCode, String message, Map<String, ?> extra) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.extra = extra == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
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
    /** {@code reason}: a NAMED staff role the caller does not hold themselves. */
    public static final String REASON_NAMED_ROLE_NOT_HELD = "named_role_not_held";
    /** {@code reason}: a code only the wildcard may hold. */
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
