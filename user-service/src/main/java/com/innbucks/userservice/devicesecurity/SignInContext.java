package com.innbucks.userservice.devicesecurity;

/** Why the app is asking (contract §6.1). */
public enum SignInContext {
    /** The customer is signing in. */
    SIGN_IN,
    /**
     * The app is silently renewing an expiring banking token (~every 15 minutes).
     * A trusted device renews without an OTP unless a hard trigger fires, which is
     * also how a removed or banned device is cut off within 15 minutes.
     */
    RENEW
}
