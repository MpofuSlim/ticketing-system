package com.innbucks.userservice.exception;

import org.springframework.http.HttpStatus;

/**
 * A refusal on the POS API key surface (V51), carrying a stable {@code errorCode}
 * rendered in {@code data.errorCode}, like {@link OrganizationException}.
 */
public class PosApiKeyException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public PosApiKeyException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() { return status; }
    public String getErrorCode() { return errorCode; }

    /**
     * Unknown key, revoked key, or an address outside the key's allow-list: one
     * answer, so the exchange is never an oracle for which keys exist.
     */
    public static PosApiKeyException rejected() {
        return new PosApiKeyException(HttpStatus.UNAUTHORIZED, "pos_key_rejected", "API key rejected.");
    }

    public static PosApiKeyException badRequest(String errorCode, String message) {
        return new PosApiKeyException(HttpStatus.BAD_REQUEST, errorCode, message);
    }
}
