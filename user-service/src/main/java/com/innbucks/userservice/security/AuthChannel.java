package com.innbucks.userservice.security;

import java.util.Locale;

/**
 * Where a login originated from. Sent by the FE as {@code X-Auth-Channel} on
 * {@code POST /auth/login}; the BE uses it to decide whether 2FA applies
 * (TOTP / any second factor is meaningless on USSD or a WhatsApp text flow).
 *
 * <p>Missing/unknown header defaults to {@link #WEB} — strictest-by-default.
 * The choice intentionally errs on the side of REQUIRING 2FA rather than
 * letting a missing header silently bypass it.
 */
public enum AuthChannel {

    /** Browser web app — full 2FA expected for system users. */
    WEB,

    /** Mobile app (iOS / Android) — full 2FA expected for system users. */
    MOBILE,

    /** USSD menu — no app, no second factor possible. */
    USSD,

    /** WhatsApp conversational flow — no app, no second factor possible. */
    WHATSAPP;

    /** Parse the {@code X-Auth-Channel} header, defaulting to WEB on null/blank/unknown. */
    public static AuthChannel parseOrDefault(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return WEB;
        }
        try {
            return AuthChannel.valueOf(headerValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return WEB;
        }
    }
}
