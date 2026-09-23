package com.innbucks.userservice.security;

import java.util.List;
import java.util.UUID;

/**
 * The organization a session acts for, as it rides the access token (V39):
 * {@code orgId}, {@code orgRole} and {@code products}.
 *
 * <p>Singular on purpose. Consumers treat {@code orgId} as authoritative
 * ownership — the marketplace will attribute listings, and so commission, to
 * it — so a session speaks for exactly one business at a time, chosen by the
 * person, never guessed by the server.
 *
 * @param orgId    the organization
 * @param orgRole  the caller's role inside it: OWNER, ADMIN or STAFF
 * @param products the organization's ACTIVE products, in bundle vocabulary
 *                 ({@code ticketing}, {@code loyalty}, {@code marketplace}), sorted
 */
public record OrgScope(UUID orgId, String orgRole, List<String> products) {
    public OrgScope {
        products = products == null ? List.of() : List.copyOf(products);
    }
}
