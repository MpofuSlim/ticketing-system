package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Registry of platform "service bundles" — high-level products a user enrols into at registration.
 *
 * <p>A bundle (e.g. {@code ticketing}) is what the user picks and what the API surfaces back in
 * {@code defaultServices}. Each bundle expands internally to the set of backend microservices the
 * user actually gets access to, and is tied to the role granted with it.
 *
 * <p>Adding a new microservice to a bundle (or adding a brand-new bundle) here is the single place
 * to update — login responses, JWT claims, and SUPER_ADMIN's auto-grant all flow from this map.
 */
public final class Services {

    public static final String TICKETING = "ticketing";
    public static final String LOYALTY   = "loyalty";

    /** Bundle definitions: bundle name -> microservices it grants access to. */
    public static final Map<String, List<String>> BUNDLES;
    /** Bundle name -> role granted alongside that bundle. */
    public static final Map<String, User.Role> BUNDLE_ROLES;
    /** All bundles. SUPER_ADMIN gets all of these. */
    public static final List<String> ALL_BUNDLES;

    static {
        Map<String, List<String>> bundles = new LinkedHashMap<>();
        bundles.put(TICKETING, List.of("events", "seats", "bookings", "payments"));
        bundles.put(LOYALTY,   List.of("loyalty", "payments"));
        BUNDLES = Map.copyOf(bundles);

        Map<String, User.Role> roles = new LinkedHashMap<>();
        roles.put(TICKETING, User.Role.EVENT_ORGANIZER);
        roles.put(LOYALTY,   User.Role.MERCHANT_ADMIN);
        BUNDLE_ROLES = Map.copyOf(roles);

        ALL_BUNDLES = List.copyOf(bundles.keySet());
    }

    private Services() {}

    public static boolean isKnownBundle(String bundle) {
        return bundle != null && BUNDLES.containsKey(bundle.trim().toLowerCase(Locale.ROOT));
    }

    /** Derive the roles granted by a set of bundle selections. */
    public static Set<User.Role> rolesFor(Collection<String> bundles) {
        Set<User.Role> roles = new LinkedHashSet<>();
        if (bundles == null) return roles;
        for (String b : bundles) {
            if (b == null) continue;
            User.Role role = BUNDLE_ROLES.get(b.trim().toLowerCase(Locale.ROOT));
            if (role != null) roles.add(role);
        }
        return roles;
    }

    /**
     * Expand bundle selections to the union of backend microservices they grant access to.
     * Used to populate the JWT {@code services} claim so downstream services can authorize per-microservice.
     */
    public static Set<String> expandToMicroservices(Collection<String> bundles) {
        Set<String> services = new LinkedHashSet<>();
        if (bundles == null) return services;
        for (String b : bundles) {
            if (b == null) continue;
            List<String> mapped = BUNDLES.get(b.trim().toLowerCase(Locale.ROOT));
            if (mapped != null) services.addAll(mapped);
        }
        return services;
    }
}
