package com.innbucks.userservice.security;

import com.innbucks.userservice.event.StaffInviteRequested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The staff invite token (V44): shape, entropy, hash-at-rest, and never printed. */
class StaffInviteTokenTest {

    @Test
    @DisplayName("STI- + 43 Base64URL characters (32 random bytes, no padding), all distinct")
    void shape() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String t = StaffInviteTokens.generate();
            assertThat(t).startsWith("STI-").hasSize(StaffInviteTokens.LENGTH).doesNotContain("=", "+", "/");
            assertThat(StaffInviteTokens.wellFormed(t)).isTrue();
            assertThat(seen.add(t)).isTrue();
        }
    }

    @Test
    @DisplayName("the stored hash is SHA-256 hex (64 chars), deterministic, and never the token")
    void hash() {
        String t = StaffInviteTokens.generate();
        String h = StaffInviteTokens.hash(t);
        assertThat(h).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(StaffInviteTokens.hash(t)).doesNotContain(t);
        assertThat(StaffInviteTokens.hash(t + "x")).isNotEqualTo(h);
    }

    @Test
    @DisplayName("malformed values are refused before any lookup")
    void wellFormed() {
        assertThat(StaffInviteTokens.wellFormed(null)).isFalse();
        assertThat(StaffInviteTokens.wellFormed("")).isFalse();
        assertThat(StaffInviteTokens.wellFormed("STI-short")).isFalse();
        assertThat(StaffInviteTokens.wellFormed("XYZ-" + "a".repeat(43))).isFalse();
        assertThat(StaffInviteTokens.wellFormed("STI-" + "a".repeat(42) + "=")).isFalse();
        assertThat(StaffInviteTokens.wellFormed("STI-" + "a".repeat(42) + "'")).isFalse();
        assertThat(StaffInviteTokens.wellFormed("STI-" + "a".repeat(43))).isTrue();
    }

    @Test
    @DisplayName("the event carrying the raw token never prints it")
    void eventRedacts() {
        String t = StaffInviteTokens.generate();
        StaffInviteRequested e = new StaffInviteRequested(7L, "tariro.moyo@innbucks.co.zw", "Tariro", t,
                List.of("CALL_CENTER_AGENT"), "admin@innbucks.co.zw", LocalDateTime.now(ZoneOffset.UTC));
        assertThat(e.toString()).doesNotContain(t).contains("redacted").contains("7");
    }
}
