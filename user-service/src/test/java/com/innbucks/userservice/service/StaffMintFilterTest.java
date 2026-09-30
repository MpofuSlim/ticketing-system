package com.innbucks.userservice.service;

import com.innbucks.userservice.config.StaffAccountProperties;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.StaffFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The mint-time backstop (V44 §2.9): watch counts, enforce withholds; SUPER_ADMIN
 * is exempt; business tokens are never touched; an eligible staff account keeps
 * everything. The Postgres-backed twin is {@code MintTimeEligibilityFilterIT}.
 */
class StaffMintFilterTest {

    private final StaffEligibility eligibility = mock(StaffEligibility.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private StaffMintFilter filter(StaffAccountProperties.Enforcement mode) {
        StaffAccountProperties properties = StaffFixtures.properties();
        properties.setEligibilityEnforcement(mode);
        StaffMintFilter filter = new StaffMintFilter(eligibility, properties);
        filter.setMeterRegistry(meters);
        return filter;
    }

    private static User user(String email, String... roles) {
        return User.builder().id(4812L).email(email).roles(new LinkedHashSet<>(List.of(roles))).build();
    }

    private double count(String reason) {
        return meters.counter(StaffMintFilter.METRIC, "reason", reason).count();
    }

    @Test
    @DisplayName("watch: an ineligible holder is minted as before, and counted by reason")
    void watch() {
        User legacy = user("farai@innbucks.co.zw", "PRODUCT_OFFICER");
        when(eligibility.ineligibility(legacy)).thenReturn(Optional.of(StaffEligibility.Ineligibility.UNVERIFIED));
        StaffMintFilter.Minted minted = filter(StaffAccountProperties.Enforcement.WATCH)
                .apply(legacy, List.of("PRODUCT_OFFICER"), List.of("events:read", "users:read"));
        assertThat(minted.roles()).containsExactly("PRODUCT_OFFICER");
        assertThat(minted.permissions()).containsExactly("events:read", "users:read");
        assertThat(count("unverified")).isEqualTo(1.0);
        assertThat(count("off_domain")).isZero();
        assertThat(count("no_profile")).isZero();
    }

    @Test
    @DisplayName("enforce: every NAMED role name and every PLATFORM code is dropped; tenant codes kept")
    void enforce() {
        User offDomain = user("po@gmail.com", "PRODUCT_OFFICER", "SUPPORT_VIEWER");
        when(eligibility.ineligibility(offDomain)).thenReturn(Optional.of(StaffEligibility.Ineligibility.OFF_DOMAIN));
        StaffMintFilter.Minted minted = filter(StaffAccountProperties.Enforcement.ENFORCE)
                .apply(offDomain, List.of("PRODUCT_OFFICER", "SUPPORT_VIEWER"),
                        List.of("users:read", "shop-staff:read"));
        assertThat(minted.roles()).containsExactly("SUPPORT_VIEWER");
        assertThat(minted.permissions()).containsExactly("shop-staff:read");
        assertThat(count("off_domain")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("SUPER_ADMIN is exempt in either mode, and eligibility is never even asked")
    void superAdminExempt() {
        User owner = user("owner@gmail.com", "SUPER_ADMIN");
        StaffMintFilter.Minted minted = filter(StaffAccountProperties.Enforcement.ENFORCE)
                .apply(owner, List.of("SUPER_ADMIN"), List.of("users:read", "staff:create"));
        assertThat(minted.roles()).containsExactly("SUPER_ADMIN");
        assertThat(minted.permissions()).containsExactly("users:read", "staff:create");
        verify(eligibility, never()).ineligibility(any());
    }

    @Test
    @DisplayName("a business token (no NAMED role, no PLATFORM code) is untouched and never evaluated")
    void businessUntouched() {
        User merchant = user("rudo@shop.co.zw", "MERCHANT_ADMIN");
        StaffMintFilter.Minted minted = filter(StaffAccountProperties.Enforcement.ENFORCE)
                .apply(merchant, List.of("MERCHANT_ADMIN"), List.of("shop-admins:write", "shop-staff:read"));
        assertThat(minted.permissions()).containsExactly("shop-admins:write", "shop-staff:read");
        verify(eligibility, never()).ineligibility(any());
    }

    @Test
    @DisplayName("an eligible staff account keeps everything in enforce mode")
    void eligibleKeeps() {
        User agent = user("tariro.moyo@innbucks.co.zw", "CALL_CENTER_AGENT");
        when(eligibility.ineligibility(agent)).thenReturn(Optional.empty());
        StaffMintFilter.Minted minted = filter(StaffAccountProperties.Enforcement.ENFORCE)
                .apply(agent, List.of("CALL_CENTER_AGENT"),
                        List.of("device-security:manage", "device-security:read"));
        assertThat(minted.roles()).containsExactly("CALL_CENTER_AGENT");
        assertThat(minted.permissions()).containsExactly("device-security:manage", "device-security:read");
    }

    @Test
    @DisplayName("a NAMED role emptied of permissions still counts as staff authority (booking grants by name)")
    void namedRoleWithoutPermissions() {
        User legacy = user("pm@innbucks.co.zw", "PRODUCT_MANAGER");
        when(eligibility.ineligibility(legacy)).thenReturn(Optional.of(StaffEligibility.Ineligibility.NO_PROFILE));
        StaffMintFilter.Minted minted = filter(StaffAccountProperties.Enforcement.ENFORCE)
                .apply(legacy, List.of("PRODUCT_MANAGER"), List.of());
        assertThat(minted.roles()).isEmpty();
        assertThat(count("no_profile")).isEqualTo(1.0);
    }
}
