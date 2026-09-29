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
     * the admin API.
     */
    public static StaffPolicyException targetNotManageable(String reason) {
        return new StaffPolicyException(HttpStatus.FORBIDDEN, TARGET_NOT_MANAGEABLE,
                TARGET_NOT_MANAGEABLE_MESSAGE, Map.of("reason", reason));
    }
}
