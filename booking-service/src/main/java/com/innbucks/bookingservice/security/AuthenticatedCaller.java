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

    /**
     * Roles whose holders read the platform WIDE — not scoped to one
     * organizer's events. The single definition of "platform staff"; every
     * read-scope check goes through {@link #isPlatformStaff} rather than
     * testing {@code ROLE_SUPER_ADMIN} inline, so a new staff role is added
     * here once instead of being missed at one of a dozen call sites.
     *
     * <p><b>Read scope only.</b> This must never gate a privileged WRITE
     * (cancel, reverse, edit, delete): those keep their explicit
     * {@code hasRole('SUPER_ADMIN')} check, so widening this set can never
     * silently hand a reporting role the ability to move money or destroy
     * data. PRODUCT_MANAGER's writes — ticket resend, event approve/reject —
     * are granted one at a time by {@code @PreAuthorize}, never by this.
     */
    private static final java.util.Set<String> PLATFORM_STAFF_ROLES = java.util.Set.of(
            "ROLE_SUPER_ADMIN", "ROLE_PRODUCT_OFFICER", "ROLE_PRODUCT_MANAGER");

    /**
     * True when the caller sees every organizer's data rather than only their
     * own. Drives the {@code isAdmin} scope flag the report services take.
     */
    public static boolean isPlatformStaff(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> PLATFORM_STAFF_ROLES.contains(a.getAuthority()));
    }

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
