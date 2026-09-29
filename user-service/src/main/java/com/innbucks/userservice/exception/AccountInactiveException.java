package com.innbucks.userservice.exception;

/**
 * A session was about to be continued or minted for a DEACTIVATED account.
 * Thrown by every place that turns a credential into a session — refresh
 * rotation ({@code /auth/refresh}, {@code /auth/organization-context}), the MFA
 * step ({@code /auth/login/mfa}, {@code /auth/mfa/enroll/*}) and the mint
 * chokepoint itself — so deactivation ends sessions at once rather than when
 * the refresh chain happens to run out.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as
 * {@code 401 {"errorCode": "account_inactive"}}: the credential is genuine but no
 * longer admits anyone, so the FE drops the stored tokens and routes to sign-in,
 * where the password step explains the rest. The message is a typed constant —
 * safe to pass through.
 */
public class AccountInactiveException extends RuntimeException {

    public static final String MESSAGE =
            "This account has been deactivated. Contact your administrator to restore access.";

    public AccountInactiveException() {
        super(MESSAGE);
    }
}
