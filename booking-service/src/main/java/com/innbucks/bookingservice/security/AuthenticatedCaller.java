package com.innbucks.bookingservice.security;

import org.springframework.security.core.Authentication;

import java.util.UUID;

/**
 * Read-side helper for the {@link JwtAuthDetails} record that
 * {@link JwtFilter} stashes on {@link Authentication#getDetails()}.
 * Hides the instanceof + record-accessor calls so callers can read
 * the JWT's cross-service UUID claims with one line and without
 * depending on the filter's internal details type.
 *
 * <p>Returns null when the claim wasn't present on the token (a
 * pre-V20 token). Callers that require the value must throw 401
 * themselves — some sites (e.g. the ticket-scan happy path)
 * require it, others (the legacy booking-create flow) treat null
 * as "fall back to email".
 */
public final class AuthenticatedCaller {
    private AuthenticatedCaller() {}

    public static UUID userUuid(Authentication auth) {
        JwtAuthDetails details = details(auth);
        return details == null ? null : details.userUuid();
    }

    public static UUID organizerUuid(Authentication auth) {
        JwtAuthDetails details = details(auth);
        return details == null ? null : details.organizerUuid();
    }

    private static JwtAuthDetails details(Authentication auth) {
        if (auth == null) return null;
        Object details = auth.getDetails();
        return details instanceof JwtAuthDetails d ? d : null;
    }
}
