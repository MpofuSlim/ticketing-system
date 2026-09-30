package com.innbucks.userservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.dto.AuthResponseDTO;
import com.innbucks.userservice.dto.MfaEnrollCompleteResponseDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.security.PermissionResolver;
import com.innbucks.userservice.testsupport.BuiltInRoleRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every response that issues a session carries {@code permissions} — the list
 * exactly as minted into the token's {@code perms} claim — so the console gates
 * its screens on what the session may do without decoding the JWT, and without
 * guessing from role names (a custom role can grant anything, and a built-in's
 * grants are editable). Additive, like {@code organizationProducts} in V39.
 */
class AuthResponsePermissionsFieldTest {

    private static final String SECRET = "test-test-test-test-test-test-test-test";

    private JwtUtil jwt;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", SECRET);
        ReflectionTestUtils.setField(jwt, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwt, "refreshExpiration", 86_400_000L);

        RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
        when(refreshTokens.issueNewFamily(any(User.class), any())).thenReturn("refresh");
        when(refreshTokens.issueNewFamily(any(User.class), any(), anyBoolean())).thenReturn("refresh");

        authService = new AuthService(mock(UserRepository.class), mock(TenantProfileRepository.class),
                mock(CustomerProfileRepository.class), mock(PasswordEncoder.class), jwt,
                mock(TokenRevocationService.class), refreshTokens,
                mock(RefreshTokenRepository.class), mock(AuditService.class));
        RoleRepository roles = mock(RoleRepository.class);
        BuiltInRoleRows.stub(roles);
        ReflectionTestUtils.setField(authService, "permissionResolver", new PermissionResolver(roles));
    }

    private static User staff(String... roles) {
        return User.builder().id(7L).userUuid(UUID.randomUUID()).email("agent@innbucks.co.zw")
                .phoneNumber("+263772123456").roles(new LinkedHashSet<>(List.of(roles)))
                .active(true).approved(true).password("x").tokenVersion(2L).build();
    }

    @Test
    @DisplayName("a call-center agent's response lists exactly the codes its token carries")
    void agentPermissionsMatchTheToken() {
        AuthResponseDTO response = authService.issueToken(staff("CALL_CENTER_AGENT"), "d");

        assertThat(response.getPermissions())
                .containsExactlyInAnyOrderElementsOf(BuiltInRoleRows.GRANTS.get("CALL_CENTER_AGENT"));
        assertThat(response.getPermissions()).isEqualTo(jwt.extractPermissions(response.getToken()));
    }

    @Test
    @DisplayName("the wildcard is always expanded — the list is concrete codes, never '*'")
    void wildcardIsExpanded() {
        AuthResponseDTO response = authService.issueToken(staff("SUPER_ADMIN"), "d");

        assertThat(response.getPermissions()).doesNotContain(PermissionCatalog.WILDCARD)
                .containsExactlyInAnyOrderElementsOf(PermissionCatalog.concrete());
        assertThat(response.getPermissions()).isEqualTo(jwt.extractPermissions(response.getToken()));
    }

    @Test
    @DisplayName("roles that grant nothing here give an empty list, not an absent one")
    void emptyWhenNothingIsGranted() {
        AuthResponseDTO response = authService.issueToken(staff("TEAM_MEMBER"), "d");
        assertThat(response.getPermissions()).isEmpty();
    }

    @Test
    @DisplayName("enrolment-complete copies the same list; it is on the wire as `permissions`")
    void enrolmentCarriesIt() throws Exception {
        AuthResponseDTO session = authService.issueToken(staff("FRAUD_DESK"), "d");
        MfaEnrollCompleteResponseDTO enrolled = MfaEnrollCompleteResponseDTO.from(session, List.of("X4Q7-K9F2"));

        assertThat(enrolled.getPermissions()).isEqualTo(session.getPermissions());
        JsonNode json = new ObjectMapper().valueToTree(enrolled);
        assertThat(json.get("permissions")).isNotNull();
        assertThat(json.get("permissions").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("a response that issues no session omits the field (NON_NULL)")
    void absentWithoutASession() {
        JsonNode json = new ObjectMapper().valueToTree(
                AuthResponseDTO.builder().mfaRequired(true).mfaToken("mfa").build());
        assertThat(json.has("permissions")).isFalse();
    }
}
