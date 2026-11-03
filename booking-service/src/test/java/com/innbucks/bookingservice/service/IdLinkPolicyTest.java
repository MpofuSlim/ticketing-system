package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.Booking;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The switch that closes the id-keyed public ticket pages (V31), and the
 * tokens that replace them.
 */
class IdLinkPolicyTest {

    private static Booking createdAt(LocalDateTime at) {
        Booking b = new Booking();
        b.setCreatedAt(at);
        return b;
    }

    private static IdLinkPolicy configured(String mode, String legacyBefore, SimpleMeterRegistry registry) {
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new IdLinkPolicy(mode, legacyBefore, provider);
    }

    @Test
    void open_isTheDefault_andServesEveryBooking() {
        IdLinkPolicy policy = configured("", "", null);

        assertThat(policy.mode()).isEqualTo(IdLinkPolicy.Mode.OPEN);
        assertThat(policy.allows(createdAt(LocalDateTime.of(2030, 1, 1, 0, 0)))).isTrue();
        assertThat(policy.allows(createdAt(null))).isTrue();
    }

    @Test
    void legacy_servesOnlyBookingsCreatedBeforeTheCutOver_readInUtc() {
        // 10:00 Harare is 08:00 UTC; created_at is stored in UTC.
        IdLinkPolicy policy = configured("legacy", "2026-11-10T10:00:00+02:00", null);

        assertThat(policy.allows(createdAt(LocalDateTime.of(2026, 11, 10, 7, 59, 59)))).isTrue();
        assertThat(policy.allows(createdAt(LocalDateTime.of(2026, 11, 10, 8, 0)))).isFalse();
        assertThat(policy.allows(createdAt(LocalDateTime.of(2026, 11, 10, 9, 30)))).isFalse();
        // No creation time: nothing proves it is old, so it is refused.
        assertThat(policy.allows(createdAt(null))).isFalse();
    }

    @Test
    void closed_servesNone() {
        IdLinkPolicy policy = configured("CLOSED", "", null);

        assertThat(policy.allows(createdAt(LocalDateTime.of(2020, 1, 1, 0, 0)))).isFalse();
    }

    @Test
    void aMisconfiguredSwitch_failsTheBoot_ratherThanGuessing() {
        assertThatThrownBy(() -> configured("legacy", "", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOOKINGS_ID_LINKS_LEGACY_BEFORE");
        assertThatThrownBy(() -> configured("half-open", "", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("open, legacy or closed");
        assertThatThrownBy(() -> configured("legacy", "2026-11-10 08:00", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ISO-8601");
        // An instant without an offset is ambiguous (Harare or UTC?): refused.
        assertThatThrownBy(() -> configured("legacy", "2026-11-10T08:00:00", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyAnswer_isCounted_soClosingCanWaitForServedToGoFlat() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IdLinkPolicy policy = configured("legacy", "2026-11-10T08:00:00Z", registry);

        policy.allows(createdAt(LocalDateTime.of(2026, 11, 1, 0, 0)));
        policy.allows(createdAt(LocalDateTime.of(2026, 11, 11, 0, 0)));
        policy.allows(createdAt(LocalDateTime.of(2026, 11, 12, 0, 0)));

        assertThat(registry.get("booking.id_link").tag("outcome", "served").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("booking.id_link").tag("outcome", "refused").counter().count()).isEqualTo(2.0);
    }

    @Test
    void bothSeries_existAtZero_beforeAnyRequest() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        configured("open", "", registry);

        assertThat(registry.get("booking.id_link").tag("outcome", "served").counter().count()).isZero();
        assertThat(registry.get("booking.id_link").tag("outcome", "refused").counter().count()).isZero();
    }

    @Test
    void linkTokens_are128RandomBits_asLowerCaseHex() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = LinkTokens.newToken();
            assertThat(token).matches(LinkTokens.PATTERN);
            assertThat(LinkTokens.isWellFormed(token)).isTrue();
            assertThat(seen.add(token)).isTrue();
        }
        assertThat(LinkTokens.isWellFormed(null)).isFalse();
        assertThat(LinkTokens.isWellFormed("0123456789ABCDEF0123456789ABCDEF")).isFalse();
        assertThat(LinkTokens.isWellFormed("0123456789abcdef0123456789abcde")).isFalse();
        assertThat(LinkTokens.isWellFormed("0123456789abcdef0123456789abcdef0")).isFalse();
    }
}
