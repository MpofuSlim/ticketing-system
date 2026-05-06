package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Registry of microservice names recognised by the platform.
 *
 * <p>Add a new service here once and it will automatically:
 * <ul>
 *   <li>be included in {@code defaultServices} for SUPER_ADMIN accounts (they always get the full set);</li>
 *   <li>be available to assign per-role via {@link #defaultsFor(java.util.Collection)}.</li>
 * </ul>
 */
public final class Services {

    public static final String EVENTS    = "events";
    public static final String SEATS     = "seats";
    public static final String BOOKINGS  = "bookings";
    public static final String PAYMENTS  = "payments";
    public static final String LOYALTY   = "loyalty";

    /** Every microservice in the platform. SUPER_ADMIN gets all of these. */
    public static final List<String> ALL = List.of(EVENTS, SEATS, BOOKINGS, PAYMENTS, LOYALTY);

    /** Services an EVENT_ORGANIZER is enrolled in by default. */
    public static final List<String> EVENT_ORGANIZER_DEFAULTS = List.of(EVENTS, SEATS, BOOKINGS, PAYMENTS);

    /** Services a MERCHANT_ADMIN is enrolled in by default. */
    public static final List<String> MERCHANT_ADMIN_DEFAULTS = List.of(LOYALTY, PAYMENTS);

    private Services() {}

    /**
     * Compute the default services for a user holding the given roles.
     * SUPER_ADMIN takes precedence and grants the full {@link #ALL} set.
     * Otherwise the union of each role's defaults is returned (insertion-ordered).
     */
    public static Set<String> defaultsFor(java.util.Collection<User.Role> roles) {
        if (roles == null || roles.isEmpty()) return Set.of();
        if (roles.contains(User.Role.SUPER_ADMIN)) {
            return new LinkedHashSet<>(ALL);
        }
        Set<String> union = new LinkedHashSet<>();
        for (User.Role role : roles) {
            switch (role) {
                case EVENT_ORGANIZER -> union.addAll(EVENT_ORGANIZER_DEFAULTS);
                case MERCHANT_ADMIN  -> union.addAll(MERCHANT_ADMIN_DEFAULTS);
                default -> { /* CUSTOMER and SUPER_ADMIN handled above */ }
            }
        }
        return union;
    }
}
