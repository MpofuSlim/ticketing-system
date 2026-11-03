package com.innbucks.userservice.exception;

import org.springframework.http.HttpStatus;

/**
 * A refusal on the organization surface, carrying a stable {@code errorCode}
 * the FE branches on (rendered in {@code data.errorCode}, the same convention
 * as {@code account_pending_approval} and {@code mfa_enrollment_required}).
 * Messages are typed constants written for a person to read, so the handler
 * passes them through.
 */
public class OrganizationException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final java.util.Map<String, Object> extra;

    public OrganizationException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, java.util.Map.of());
    }

    /** With further {@code data} fields beside {@code errorCode} (e.g. {@code liveEvents}). */
    public OrganizationException(HttpStatus status, String errorCode, String message,
                                 java.util.Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.extra = extra == null ? java.util.Map.of() : java.util.Map.copyOf(extra);
    }

    public HttpStatus getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public java.util.Map<String, Object> getExtra() { return extra; }

    /**
     * Not a member, or no such organization — deliberately the same answer, so
     * the surface is never an oracle for which organizations exist.
     */
    public static OrganizationException notFound() {
        return new OrganizationException(HttpStatus.NOT_FOUND, "organization_not_found",
                "We couldn't find that organization.");
    }

    public static OrganizationException roleInsufficient(String message) {
        return new OrganizationException(HttpStatus.FORBIDDEN, "organization_role_insufficient", message);
    }
}
