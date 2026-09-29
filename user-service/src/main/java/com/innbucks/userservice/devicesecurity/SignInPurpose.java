package com.innbucks.userservice.devicesecurity;

/**
 * What a client-service token is for (contract §5.7, §6.1). The login ticket
 * carries it, and the broker forwards a staging call only with a ticket of the
 * matching purpose.
 */
public enum SignInPurpose {
    /** The user login ({@code /auth/client-service/user/login}). */
    SIGN_IN,
    /** First PIN or PIN reset ({@code /auth/client-service/pin-issue}). Always needs an OTP. */
    PIN_ISSUE,
    /** "Is this number a customer" ({@code /msisdn/:msisdn/validate}). Never an OTP, tightly rate limited. */
    LOOKUP,
    /**
     * An in-session re-check (§5.5). Never sent by the app on
     * {@code /auth/client-service}; used internally for step-up challenges.
     */
    SESSION_STEP_UP
}
