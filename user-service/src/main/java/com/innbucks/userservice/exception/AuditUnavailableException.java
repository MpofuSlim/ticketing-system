package com.innbucks.userservice.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * An audit row that MUST be written could not be — thrown by
 * {@code AuditService.recordRequired}, so the change it was recording rolls back
 * with it.
 *
 * <p>Used for the changes to who can do what (role grants, role edits, role
 * deletion): the audit trail is the only record of those once roles are data, so
 * a grant that could not be recorded is not made. Rendered as
 * {@code 503 audit_unavailable} — the server could not do its part; retrying the
 * same request later is correct.
 *
 * <p>A {@link StaffPolicyException} so it renders through the same handler and
 * envelope as the other refusals of an administrative action.
 */
public class AuditUnavailableException extends StaffPolicyException {

    public static final String AUDIT_UNAVAILABLE = "audit_unavailable";
    public static final String MESSAGE = "We couldn't record this change, so it wasn't made. Try again.";

    public AuditUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, AUDIT_UNAVAILABLE, MESSAGE, Map.of());
        if (cause != null) initCause(cause);
    }
}
