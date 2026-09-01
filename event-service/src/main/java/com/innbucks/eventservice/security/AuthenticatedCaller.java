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

    /**
     * Roles whose holders read the platform WIDE — not scoped to one
     * organizer's events. Mirrors booking-service's definition; the two must
     * stay in step or a role sees reports in one service and not the other.
     *
     * <p><b>Read scope only.</b> Never gates a WRITE: updateEvent,
     * activateEvent, deactivateEvent and deleteEvent keep their explicit
     * ROLE_SUPER_ADMIN comparison, so widening this set cannot silently let a
     * reporting role edit or destroy an event. PRODUCT_MANAGER's one write —
     * approve/reject — is granted explicitly by {@code @PreAuthorize}.
     */
    private static final java.util.Set<String> PLATFORM_STAFF_ROLES = java.util.Set.of(
            "ROLE_SUPER_ADMIN", "ROLE_PRODUCT_OFFICER", "ROLE_PRODUCT_MANAGER");

    /** True when the caller sees every organizer's events, not just their own. */
    public static boolean isPlatformStaff(org.springframework.security.core.Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> PLATFORM_STAFF_ROLES.contains(a.getAuthority()));
    }
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
