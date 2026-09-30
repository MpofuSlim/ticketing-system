package com.innbucks.userservice.support;

import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.SupportPolicyException;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The agent is resolved LIVE from their account — the self-action rule compares
 * the customer's keys with what the account holds now — and every
 * {@code /admin/support/**} call needs one that resolves (403
 * {@code agent_not_resolved}).
 */
class SupportAgentResolverTest {

    private UserRepository users;
    private StaffProfileRepository profiles;
    private SupportAgentResolver resolver;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        profiles = mock(StaffProfileRepository.class);
        resolver = new SupportAgentResolver(users, profiles);
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(users.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
    }

    private static UsernamePasswordAuthenticationToken auth(String subject) {
        return new UsernamePasswordAuthenticationToken(subject, null,
                List.of(new SimpleGrantedAuthority("support-console:read")));
    }

    @Test
    @DisplayName("an active account resolves: id, uuid, lower-cased email, sign-in and contact phones, authorities")
    void activeAccountResolves() {
        UUID uuid = UUID.randomUUID();
        when(users.findByEmail("Agent.One@innbucks.co.zw")).thenReturn(Optional.of(User.builder().id(7L).userUuid(uuid)
                .email("Agent.One@innbucks.co.zw").active(true).build()));
        when(profiles.findById(7L)).thenReturn(Optional.of(StaffProfile.builder().userId(7L)
                .contactPhone("+263772000001").build()));
        SupportAgent agent = resolver.require(auth("Agent.One@innbucks.co.zw"));
        assertThat(agent.resolved()).isTrue();
        assertThat(agent.userUuid()).isEqualTo(uuid);
        assertThat(agent.email()).isEqualTo("agent.one@innbucks.co.zw");
        assertThat(agent.contactPhone()).isEqualTo("+263772000001");
        assertThat(agent.holds("support-console:read")).isTrue();
        assertThat(agent.limiterKey()).isEqualTo(uuid.toString());
    }

    @Test
    @DisplayName("an unknown subject, a deactivated account, or no authentication is 403 agent_not_resolved")
    void unresolvedIsRefused() {
        when(users.findByPhoneNumber("+263772000002")).thenReturn(Optional.of(User.builder().id(8L)
                .userUuid(UUID.randomUUID()).phoneNumber("+263772000002").active(false).build()));
        for (var a : new UsernamePasswordAuthenticationToken[] {auth("ghost@innbucks.co.zw"), auth("+263772000002"),
                null}) {
            assertThat(resolver.resolve(a).resolved()).isFalse();
            assertThatThrownBy(() -> resolver.require(a))
                    .isInstanceOf(SupportPolicyException.class)
                    .satisfies(e -> {
                        assertThat(((SupportPolicyException) e).getErrorCode()).isEqualTo("agent_not_resolved");
                        assertThat(((SupportPolicyException) e).getStatus().value()).isEqualTo(403);
                    });
        }
        // An unresolved agent is still counted, and never as someone else.
        assertThat(resolver.resolve(auth("ghost@innbucks.co.zw")).limiterKey()).startsWith("sub-");
    }
}
