package com.innbucks.userservice.security;

/**
 * Thrown when a customer KYC-upgrade verification token fails validation or is
 * replayed. Mapped to HTTP 401 by {@code GlobalExceptionHandler}. The
 * {@link Reason} is logged (never returned verbatim to the client) so support
 * can distinguish a tampered token from an expired one from a replay without
 * the response telling an attacker which check failed.
 */
public class KycVerificationTokenException extends RuntimeException {

    public enum Reason {
        /** No token supplied. */
        MISSING,
        /** Not a parseable JWS, or missing subject/jti/exp. */
        MALFORMED,
        /** Signature, issuer or audience did not validate. */
        INVALID,
        /** Past its {@code exp}. */
        EXPIRED,
        /** Valid token, but not minted for the KYC-upgrade purpose. */
        PURPOSE_MISMATCH,
        /** Valid token, but bound to a different phone than the request targets. */
        PHONE_MISMATCH,
        /** Single-use token presented a second time. */
        REPLAYED
    }

    private final transient Reason reason;

    public KycVerificationTokenException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
