package com.innbucks.userservice.support;

import com.innbucks.userservice.exception.SupportPolicyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lookup binding (design §3.1.5 step 2, T27): a detail read or write needs the
 * agent's OWN, fresh lookup, which returned the section, with the target inside
 * it. Unknown, someone else's and stale are the same answer.
 */
class SupportLookupBindingTest {

    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");
    private static final UUID AGENT = UUID.fromString("7d1e2f3a-4b5c-4d6e-8f70-9a1b2c3d4e5f");

    private SupportAccessLogRepository log;
    private SupportLookupBinding binding;

    @BeforeEach
    void setUp() {
        log = mock(SupportAccessLogRepository.class);
        SupportProperties props = new SupportProperties();
        props.afterPropertiesSet();
        binding = new SupportLookupBinding(log, props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SupportAgent agent(UUID uuid) {
        return new SupportAgent("agent@innbucks.co.zw", 7L, uuid, "agent@innbucks.co.zw", null, null, Set.of());
    }

    private void lookup(String id, UUID agent, LocalDateTime at, String sections, Map<String, List<String>> targets) {
        SupportCustomerKeys keys = new SupportCustomerKeys(List.of("+263771234567"), List.of("tariro@example.com"),
                List.of(), List.of(1042L), null);
        when(log.findSearch(id)).thenReturn(Optional.of(SupportAccessLog.builder()
                .lookupId(id).op("SEARCH").outcome("OK").agentUserUuid(agent).agentSubject("x")
                .createdAt(at).sections(sections)
                .customerKeys(SupportAccessLogWriter.json(keys))
                .sectionTargets(SupportAccessLogWriter.json(targets)).build()));
    }

    private static LocalDateTime minutesAgo(long m) {
        return LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(m);
    }

    @Test
    @DisplayName("the agent's own fresh lookup, the right section, a target inside it: bound, with its keys")
    void bound() {
        lookup("SLK-7Q2M9X", AGENT, minutesAgo(29), "console,innbucksApp", Map.of("console", List.of("1042")));
        SupportLookupBinding.Bound b = binding.bind(agent(AGENT), " slk-7q2m9x ", "console", "1042");
        assertThat(b.lookupId()).isEqualTo("SLK-7Q2M9X");
        assertThat(b.keys().phones()).containsExactly("+263771234567");
        assertThat(b.keys().userIds()).containsExactly(1042L);
    }

    @Test
    @DisplayName("no lookupId: 400 lookup_required")
    void missing() {
        for (String blank : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> binding.bind(agent(AGENT), blank, "console", "1042"))
                    .satisfies(e -> assertThat(((SupportPolicyException) e).getErrorCode()).isEqualTo("lookup_required"));
        }
    }

    @Test
    @DisplayName("stale, someone else's, unknown and malformed are all 409 lookup_expired — no oracle")
    void expiredForeignUnknownMalformed() {
        lookup("SLK-7Q2M9X", AGENT, minutesAgo(31), "console", Map.of("console", List.of("1042")));
        lookup("SLK-3H8KD2", UUID.randomUUID(), minutesAgo(1), "console", Map.of("console", List.of("1042")));
        for (String id : new String[] {"SLK-7Q2M9X", "SLK-3H8KD2", "SLK-000000", "not-an-id"}) {
            assertThatThrownBy(() -> binding.bind(agent(AGENT), id, "console", "1042"))
                    .as(id)
                    .satisfies(e -> {
                        SupportPolicyException s = (SupportPolicyException) e;
                        assertThat(s.getErrorCode()).isEqualTo("lookup_expired");
                        assertThat(s.getStatus().value()).isEqualTo(409);
                        assertThat(s.getExtra()).isEmpty();
                    });
        }
    }

    @Test
    @DisplayName("an unresolved agent never binds, even to a lookup with a null agent")
    void unresolvedAgent() {
        lookup("SLK-7Q2M9X", null, minutesAgo(1), "console", Map.of("console", List.of("1042")));
        assertThatThrownBy(() -> binding.bind(agent(null), "SLK-7Q2M9X", "console", "1042"))
                .satisfies(e -> assertThat(((SupportPolicyException) e).getErrorCode()).isEqualTo("lookup_expired"));
    }

    @Test
    @DisplayName("a lookup that did not return the section (a reference search): 409 with reason section_not_in_lookup")
    void sectionNotReturned() {
        lookup("SLK-7Q2M9X", AGENT, minutesAgo(1), "innbucksApp", Map.of("innbucksApp", List.of("+263771234567")));
        assertThatThrownBy(() -> binding.bind(agent(AGENT), "SLK-7Q2M9X", "console", "1042"))
                .satisfies(e -> {
                    SupportPolicyException s = (SupportPolicyException) e;
                    assertThat(s.getErrorCode()).isEqualTo("lookup_expired");
                    assertThat(s.getExtra()).containsEntry("reason", "section_not_in_lookup");
                });
    }

    @Test
    @DisplayName("T27: an id outside the lookup is 404 target_not_found — walking ids finds nothing")
    void targetOutsideTheLookup() {
        lookup("SLK-7Q2M9X", AGENT, minutesAgo(1), "console", Map.of("console", List.of("1042")));
        for (String target : new String[] {"1041", "1043", "1", "+263771234567"}) {
            assertThatThrownBy(() -> binding.bind(agent(AGENT), "SLK-7Q2M9X", "console", target))
                    .as(target)
                    .satisfies(e -> {
                        SupportPolicyException s = (SupportPolicyException) e;
                        assertThat(s.getErrorCode()).isEqualTo("target_not_found");
                        assertThat(s.getStatus().value()).isEqualTo(404);
                    });
        }
    }

    @Test
    @DisplayName("lookup ids: SLK- plus six Crockford characters, from a secure random")
    void lookupIdFormat() {
        for (int i = 0; i < 200; i++) {
            String id = SupportLookupIds.next();
            assertThat(SupportLookupIds.wellFormed(id)).as(id).isTrue();
            assertThat(id.substring(4)).doesNotContain("I", "L", "O", "U");
        }
        assertThat(SupportLookupIds.wellFormed("slk-7q2m9x")).isFalse();
        assertThat(SupportLookupIds.wellFormed("SLK-7Q2M9")).isFalse();
    }
}
