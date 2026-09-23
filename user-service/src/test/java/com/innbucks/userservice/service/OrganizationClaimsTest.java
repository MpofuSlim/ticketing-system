package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.AuthResponseDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import com.innbucks.userservice.security.OrgScope;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * What the organization claims (V39) add to a token — and, as important, what
 * they leave alone.
 *
 * <p>Ticketing is live and reads none of this, so the first two cases are the
 * contract that matters most: an account with no organization gets exactly the
 * token it got before V39, and an account with one gets exactly three more keys
 * and every existing claim unchanged.
 */
class OrganizationClaimsTest {

    private static final String SECRET = "test-test-test-test-test-test-test-test";

    private JwtUtil jwt;
    private OrganizationService organizations;
    private AuthService authService;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", SECRET);
        ReflectionTestUtils.setField(jwt, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwt, "refreshExpiration", 86_400_000L);

        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        when(refreshTokenService.issueNewFamily(any(User.class), any())).thenReturn("refresh");
        when(refreshTokenService.issueNewFamily(any(User.class), any(), anyBoolean())).thenReturn("refresh");

        organizations = mock(OrganizationService.class);
        authService = new AuthService(mock(UserRepository.class), mock(TenantProfileRepository.class),
                mock(CustomerProfileRepository.class), mock(PasswordEncoder.class), jwt,
                mock(TokenRevocationService.class), refreshTokenService,
                mock(RefreshTokenRepository.class), mock(AuditService.class));
    }

    private void withOrganizations() {
        ReflectionTestUtils.setField(authService, "organizationService", organizations);
    }

    private static User organizer() {
        return User.builder()
                .id(7L).userUuid(UUID.fromString("3f6c1a2b-7d8e-4f90-a1b2-c3d4e5f60718"))
                .email("rudo@example.com").phoneNumber("+263772123456")
                .roles(new HashSet<>(Set.of("EVENT_ORGANIZER")))
                .defaultServices(new HashSet<>(Set.of("ticketing")))
                .firstName("Rudo").lastName("Chikwanha")
                .active(true).approved(true).password("x").tokenVersion(2L).build();
    }

    private static Claims claims(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(token).getPayload();
    }

    /** Claims that differ per mint by construction (timestamps), removed before comparing shapes. */
    private static Map<String, Object> stable(Claims c) {
        Map<String, Object> m = new TreeMap<>(c);
        m.remove("iat");
        m.remove("exp");
        return m;
    }

    @Test
    @DisplayName("no organization: the token is exactly the pre-V39 token")
    void noOrganizationLeavesTokenUnchanged() {
        Map<String, Object> before = stable(claims(authService.issueToken(organizer(), "d").getToken()));

        withOrganizations();
        when(organizations.defaultOrganizationFor(any())).thenReturn(null);
        when(organizations.scopeFor(any(), any())).thenReturn(Optional.empty());
        AuthResponseDTO after = authService.issueToken(organizer(), "d");

        assertThat(stable(claims(after.getToken()))).isEqualTo(before);
        assertThat(after.getOrganizationId()).isNull();
        assertThat(after.getOrganizationSelectionRequired()).isNull();
    }

    @Test
    @DisplayName("one organization: exactly three claims are added and every existing claim is unchanged")
    void oneOrganizationAddsExactlyThreeClaims() {
        Map<String, Object> before = stable(claims(authService.issueToken(organizer(), "d").getToken()));

        withOrganizations();
        when(organizations.defaultOrganizationFor(any())).thenReturn(orgId);
        when(organizations.scopeFor(any(), eq(orgId)))
                .thenReturn(Optional.of(new OrgScope(orgId, "OWNER", List.of("ticketing"))));
        AuthResponseDTO after = authService.issueToken(organizer(), "d");
        Map<String, Object> now = stable(claims(after.getToken()));

        assertThat(now.keySet()).containsAll(before.keySet());
        Set<String> added = new TreeSet<>(now.keySet());
        added.removeAll(before.keySet());
        assertThat(added).containsExactly("orgId", "orgRole", "products");
        before.forEach((k, v) -> assertThat(now.get(k)).as("claim %s", k).isEqualTo(v));

        assertThat(now.get("orgId")).isEqualTo(orgId.toString());
        assertThat(now.get("orgRole")).isEqualTo("OWNER");
        assertThat(now.get("products")).isEqualTo(List.of("ticketing"));
        assertThat(after.getOrganizationId()).isEqualTo(orgId);
        assertThat(after.getOrganizationRole()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("several organizations and none chosen: no scope, and the response asks for a choice")
    void severalOrganizationsAskForAChoice() {
        withOrganizations();
        when(organizations.defaultOrganizationFor(any())).thenReturn(null);
        when(organizations.scopeFor(any(), any())).thenReturn(Optional.empty());
        when(organizations.selectionRequired(any(), eq(null))).thenReturn(true);

        AuthResponseDTO response = authService.issueToken(organizer(), "d");

        assertThat(claims(response.getToken())).doesNotContainKeys("orgId", "orgRole", "products");
        assertThat(response.getOrganizationSelectionRequired()).isTrue();
    }

    @Test
    @DisplayName("a phone proof never carries an organization, whatever the account belongs to")
    void phoneProofWithholdsOrganization() {
        withOrganizations();
        User shopkeeper = organizer();
        shopkeeper.getRoles().add("CUSTOMER");
        CustomerProfileRepository customers = (CustomerProfileRepository)
                ReflectionTestUtils.getField(authService, "customerProfileRepository");
        when(customers.findByUserId(7L)).thenReturn(Optional.of(
                com.innbucks.userservice.entity.CustomerProfile.builder().registrationTier(1).build()));

        AuthResponseDTO response = authService.issuePhoneProofToken(shopkeeper, "d");

        assertThat(claims(response.getToken())).doesNotContainKeys("orgId", "orgRole", "products");
        assertThat(response.getOrganizationId()).isNull();
        assertThat(response.getOrganizationSelectionRequired()).isNull();
        verify(organizations, never()).scopeFor(any(), any());
    }
}
