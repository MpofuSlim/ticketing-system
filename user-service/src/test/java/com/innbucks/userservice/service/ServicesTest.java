package com.innbucks.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.innbucks.userservice.entity.User;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the bundle registry as an executable spec: every product bundle, its
 * role grant, and its microservice expansion. A bundle silently dropped or a
 * role remapped changes login responses, JWT claims and SUPER_ADMIN's
 * auto-grant fleet-wide — this test makes that a conscious edit.
 */
class ServicesTest {

    @Test
    void exactlyTheExpectedBundlesExist() {
        assertThat(Services.ALL_BUNDLES)
                .containsExactly(Services.TICKETING, Services.LOYALTY, Services.MARKETPLACE);
    }

    @Test
    void marketplaceBundleGrantsMerchantAdminAndItsMicroservices() {
        // Owner decision (2026-08-05): marketplace administration is
        // MERCHANT_ADMIN-only — the bundle rides the same role as loyalty.
        assertThat(Services.isKnownBundle("marketplace")).isTrue();
        assertThat(Services.isKnownBundle(" MARKETPLACE ")).isTrue();
        assertThat(Services.rolesFor(List.of(Services.MARKETPLACE)))
                .containsExactly(User.Role.MERCHANT_ADMIN);
        assertThat(Services.expandToMicroservices(List.of(Services.MARKETPLACE)))
                .containsExactly("marketplace", "payments");
    }

    @Test
    void existingBundlesAreUnchanged() {
        assertThat(Services.rolesFor(List.of(Services.TICKETING)))
                .containsExactly(User.Role.EVENT_ORGANIZER);
        assertThat(Services.expandToMicroservices(List.of(Services.TICKETING)))
                .containsExactly("events", "seats", "bookings", "payments");
        assertThat(Services.rolesFor(List.of(Services.LOYALTY)))
                .containsExactly(User.Role.MERCHANT_ADMIN);
        assertThat(Services.expandToMicroservices(List.of(Services.LOYALTY)))
                .containsExactly("loyalty", "payments");
    }

    @Test
    void loyaltyAndMarketplaceShareOneRoleGrant() {
        // Both bundles map to MERCHANT_ADMIN — requesting both must not
        // duplicate the role.
        assertThat(Services.rolesFor(List.of(Services.LOYALTY, Services.MARKETPLACE)))
                .containsExactly(User.Role.MERCHANT_ADMIN);
    }

    @Test
    void unknownBundlesAreRejected() {
        assertThat(Services.isKnownBundle("wallet")).isFalse();
        assertThat(Services.isKnownBundle(null)).isFalse();
        assertThat(Services.rolesFor(List.of("wallet"))).isEmpty();
        assertThat(Services.expandToMicroservices(List.of("wallet"))).isEmpty();
    }
}
