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

    /**
     * Parse the {@code X-Auth-Channel} header, defaulting to WEB on null/blank/unknown.
     *
     * <p><b>Trusted callers only.</b> This can return USSD/WHATSAPP, which
     * {@link MfaPolicy} treats as "no second factor" — so it must never be fed
     * an untrusted, client-supplied header on a public login. Use it only behind
     * the internal trust boundary (e.g. a server-side USSD/WhatsApp adapter).
     * For the public {@code POST /auth/login} edge, use {@link #forPublicLogin}.
     */
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

    /**
     * Parse the {@code X-Auth-Channel} header from an <b>untrusted public
     * caller</b> (the {@code POST /auth/login} edge), clamping to the app
     * channels ({@link #WEB}, {@link #MOBILE}) that a browser/app login can
     * legitimately assert.
     *
     * <p>USSD and WhatsApp are the channels {@link MfaPolicy} treats as
     * second-factor-free; a genuine USSD/WhatsApp login originates from a
     * trusted server-side adapter, never the public edge. Honouring those two
     * from a client header let anyone holding a valid password send
     * {@code X-Auth-Channel: USSD} and skip MFA entirely — an unauthenticated
     * header must never weaken authentication. So they (and any unknown/blank
     * value) collapse to {@link #WEB}; only an explicit {@code MOBILE} survives.
     */
    public static AuthChannel forPublicLogin(String headerValue) {
        return parseOrDefault(headerValue) == MOBILE ? MOBILE : WEB;
    }
}
