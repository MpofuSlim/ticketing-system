package com.innbucks.userservice.service;

/**
 * Enumeration of event types written to {@code audit_events}.
 *
 * <p>Adding a new value here is the right way to surface a new
 * sensitive action — the underlying column is {@code VARCHAR(64)}
 * to keep this flexible without an enum-table join.
 *
 * <p>Naming convention: {@code <DOMAIN>_<ACTION>_<RESULT?>} where
 * the suffix is omitted when there's no SUCCESS / FAILURE polarity
 * worth distinguishing in the type itself (the {@code outcome}
 * column carries that).
 */
public enum AuditEventType {
    /** Successful /auth/login. */
    AUTH_LOGIN_SUCCESS,
    /**
     * Failed /auth/login. The {@code failure_reason} narrows it:
     * {@code unknown_identifier}, {@code wrong_password},
     * {@code account_inactive}.
     */
    AUTH_LOGIN_FAILURE,
    /**
     * Locked-account /auth/login attempt — fired when the user
     * tries to authenticate against a row whose {@code locked_until}
     * is still in the future. Separate from AUTH_LOGIN_FAILURE so
     * dashboards can break out "locked account being probed" from
     * "wrong-password volume".
     */
    AUTH_LOGIN_REJECTED_LOCKED,
    /**
     * Lockout threshold crossed on this attempt — i.e. the 7th
     * consecutive wrong-pw landed and {@code locked_until} just
     * got stamped. Pairs with the AUTH_LOGIN_FAILURE that fired on
     * the same request.
     */
    AUTH_ACCOUNT_LOCKED,
    /** Successful /auth/refresh — token family rotated. */
    AUTH_REFRESH_SUCCESS,
    /**
     * /auth/refresh fired with a token that was already rotated
     * (replay) — the entire family is revoked as a side effect.
     */
    AUTH_REFRESH_REUSE_DETECTED,
    /**
     * /auth/refresh from a device id that doesn't match the one
     * the family was bound to at login — family revoked.
     */
    AUTH_REFRESH_DEVICE_MISMATCH,
    /** /auth/logout — explicit user-initiated session termination. */
    AUTH_LOGOUT,
    /** /auth/change-password completed. */
    AUTH_PASSWORD_CHANGED,
    /** Rate-limit threshold exceeded on /auth/login or /auth/refresh. */
    AUTH_RATE_LIMITED,

    /**
     * SUPER_ADMIN approved a system user's first activation — combines the
     * activation itself with the assignment of a one-time temporary password
     * (randomly generated per user, force-changed on first login). Fires at
     * most once per user (the {@code approved} flag makes the path one-shot).
     * Distinct from {@link #USER_ACTIVATED} so dashboards can break out "new
     * account approvals" (compliance-relevant) from routine reactivation traffic.
     */
    USER_APPROVED,
    /**
     * SUPER_ADMIN re-activated an already-approved user — admin lifted a
     * previous deactivation, no password reset involved.
     */
    USER_ACTIVATED,
    /** SUPER_ADMIN deactivated a user — account can no longer log in. */
    USER_DEACTIVATED,
    /**
     * SUPER_ADMIN reset a system user's temporary password — mints a fresh
     * random password, re-flags must-change, and re-delivers it. The recovery
     * path for when the original onboarding notification never reached the
     * user (every delivery channel failed). Never fired for a SUPER_ADMIN
     * target (that credential is owned by the bootstrap env seed).
     */
    USER_TEMP_PASSWORD_RESET
}
