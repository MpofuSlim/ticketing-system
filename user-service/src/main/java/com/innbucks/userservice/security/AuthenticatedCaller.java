package com.innbucks.userservice.security;

import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.UUID;

/**
 * Read-side helper for the JWT claim map that {@link JwtFilter} stashes
 * on {@link Authentication#getDetails()}. Hides the cast + key lookup so
 * controllers can read the caller's stable user / organizer UUID with
 * one line and without depending on the filter's internal map shape.
 *
 * <p>Returns {@code null} when the claim wasn't present on the token (a
 * pre-V20 token, or a role with no organizer context like CUSTOMER).
 * Callers that require the value must throw 401 themselves — that
 * decision belongs in the controller, not the helper, because some
 * call sites (e.g. listing endpoints that fall back to a different
 * scope) legitimately want to handle the null case.
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
