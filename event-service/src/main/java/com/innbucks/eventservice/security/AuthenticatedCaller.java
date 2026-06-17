package com.innbucks.eventservice.security;

import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.UUID;

/**
 * Read-side helper for the JWT claim map that {@link JwtFilter} stashes
 * on {@link Authentication#getDetails()}. Controllers read the caller's
 * stable user / organizer UUID with one line and without depending on
 * the filter's internal map shape.
 */
public final class AuthenticatedCaller {
    private AuthenticatedCaller() {}

    public static UUID userUuid(Authentication auth) {
        return readUuid(auth, AuthDetailsKeys.USER_UUID);
    }

    public static UUID organizerUuid(Authentication auth) {
        return readUuid(auth, AuthDetailsKeys.ORGANIZER_UUID);
    }

    @SuppressWarnings("unchecked")
    private static UUID readUuid(Authentication auth, String key) {
        if (auth == null) return null;
        Object details = auth.getDetails();
        if (!(details instanceof Map<?, ?> map)) return null;
        Object value = ((Map<String, Object>) map).get(key);
        return value instanceof UUID uuid ? uuid : null;
    }
}
