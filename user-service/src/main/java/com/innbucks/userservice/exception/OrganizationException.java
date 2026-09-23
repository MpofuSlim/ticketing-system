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

    public OrganizationException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() { return status; }
    public String getErrorCode() { return errorCode; }

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
