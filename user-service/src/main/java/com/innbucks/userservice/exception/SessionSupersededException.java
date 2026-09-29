package com.innbucks.userservice.exception;

/**
 * An access token presented to a {@code /auth/**} endpoint that authenticates
 * the caller from its own Bearer header ({@code /auth/change-password},
 * {@code /auth/mfa/disable}) belongs to a session a later bump has ended — a
 * newer login, a logout, a role or password change, an admin MFA reset.
 *
 * <p>{@code JwtFilter} refuses such a token everywhere else (its
 * {@code SESSION_SUPERSEDED}), but it skips {@code /auth}, so these handlers
 * make the same check themselves. Rendered by {@link GlobalExceptionHandler} as
 * {@code 401 {"errorCode": "session_superseded"}}; the message is a typed
 * constant, safe to pass through.
 */
public class SessionSupersededException extends RuntimeException {

    public static final String MESSAGE = "This session has ended. Please sign in again.";

    public SessionSupersededException() {
        super(MESSAGE);
    }
}
