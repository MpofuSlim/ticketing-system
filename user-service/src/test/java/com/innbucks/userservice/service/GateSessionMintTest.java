package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.AuthResponseDTO;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import com.innbucks.userservice.security.PermissionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the Organizer app's gate sign-in (V57) is allowed to become: a session
 * carrying the {@code GATE_SCANNER} role and nothing else, on its first mint and
 * on every refresh, whatever the account on the number holds.
 *
 * <p>An SMS code proves a phone. A CUSTOMER session minted from one could book,
 * and for a sales agent sell for cash; an organizer's would run their portal. So
 * the gate session drops every role, permission, service and scope claim the
 * account has, and the other services confine what is left to the gate.
 */
class GateSessionMintTest {

    private static final String PHONE = "+263771234567";

    private CustomerProfileRepository customerRepo;
    private RefreshTokenService refreshTokenService;
    private JwtUtil jwt;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", "test-test-test-test-test-test-test-test");
        ReflectionTestUtils.setField(jwt, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwt, "refreshExpiration", 86_400_000L);

        customerRepo = mock(CustomerProfileRepository.class);
        refreshTokenService = mock(RefreshTokenService.class);
        when(refreshTokenService.issueGateFamily(any(User.class), any())).thenReturn("gate-refresh");
        when(refreshTokenService.issueNewFamily(any(User.class), any())).thenReturn("refresh");
        when(refreshTokenService.issueNewFamily(any(User.class), any(), anyBoolean())).thenReturn("refresh");

        authService = new AuthService(mock(UserRepository.class), mock(TenantProfileRepository.class), customerRepo,
                mock(PasswordEncoder.class), jwt,
                mock(TokenRevocationService.class), refreshTokenService,
                mock(RefreshTokenRepository.class), mock(AuditService.class));
        // A resolver that would grant codes for ANY role: the gate mint must not ask it.
        PermissionResolver resolver = mock(PermissionResolver.class);
        when(resolver.resolve(anyCollection())).thenReturn(Set.of("events:read", "bookings:write"));
        ReflectionTestUtils.setField(authService, "permissionResolver", resolver);
    }

    /** An organizer who also shops, with a portal email and a stale password-change flag. */
    private User organizerWhoShops() {
        User user = User.builder()
                .id(21L).userUuid(UUID.randomUUID()).phoneNumber(PHONE)
                .email("rudo@harare-arena.co.zw")
                .firstName("Rudo").lastName("Moyo")
                .roles(User.roleNames(User.Role.CUSTOMER, User.Role.EVENT_ORGANIZER))
                .defaultServices(Set.of("ticketing"))
                .mustChangePassword(true)
                .homeCountry("ZW")
                .active(true).approved(true).password("x").tokenVersion(4L).build();
        when(customerRepo.findByUserId(21L)).thenReturn(Optional.of(
                CustomerProfile.builder().registrationTier(3).verified(true).build()));
        return user;
    }

    @Test
    @DisplayName("the gate session carries GATE_SCANNER alone: no account role, permission or service")
    void gateSession_carriesOnlyTheGateRole() {
        User user = organizerWhoShops();

        AuthResponseDTO response = authService.issueGateScannerToken(user, "device-1");
        String token = response.getToken();

        assertThat(jwt.extractRoles(token)).containsExactly("GATE_SCANNER");
        assertThat(jwt.extractPermissions(token)).isEmpty();
        assertThat(jwt.extractServices(token)).isEmpty();
        assertThat(response.getRoles()).containsExactly("GATE_SCANNER");
        assertThat(response.getPermissions()).isEmpty();
        assertThat(response.getDefaultServices()).isEmpty();
        assertThat(response.getRefreshToken()).isEqualTo("gate-refresh");
        verify(refreshTokenService).issueGateFamily(user, "device-1");
    }

    @Test
    @DisplayName("no scope claim, no name, no email, and never the password-change flag")
    void gateSession_withholdsScopeNamesAndTheEmail() {
        User user = organizerWhoShops();

        AuthResponseDTO response = authService.issueGateScannerToken(user, "device-1");
        String token = response.getToken();

        assertThat(jwt.extractOrganizerUuid(token)).isNull();
        assertThat(jwt.extractMerchantId(token)).isNull();
        assertThat(jwt.extractShopId(token)).isNull();
        assertThat(jwt.extractOrganizationId(token)).isNull();
        assertThat(jwt.extractFirstName(token)).isNull();
        assertThat(jwt.extractLastName(token)).isNull();
        // booking-service refuses a token carrying it before the scan runs.
        assertThat(jwt.extractMustChangePassword(token)).isFalse();
        assertThat(response.isMustChangePassword()).isFalse();
        assertThat(response.getEmail()).isNull();
        assertThat(response.getOrganizationId()).isNull();
    }

    @Test
    @DisplayName("it keeps what the gate needs: the phone, the account id and the token version")
    void gateSession_keepsThePhoneAndTheAccount() {
        User user = organizerWhoShops();

        String token = authService.issueGateScannerToken(user, "device-1").getToken();

        assertThat(jwt.extractPhoneNumber(token)).isEqualTo(PHONE);
        assertThat(jwt.extractUserUuid(token)).isEqualTo(user.getUserUuid());
        assertThat(jwt.extractTokenVersion(token)).isEqualTo(4L);
    }

    @Test
    @DisplayName("a refresh of a gate family mints a gate session again, not the account's roles")
    void refresh_ofAGateFamily_staysGateOnly() {
        User user = organizerWhoShops();
        when(refreshTokenService.rotate("gate-refresh", "device-1")).thenReturn(
                new RefreshTokenService.Rotation(user, "gate-refresh-2", true, null, true));

        AuthResponseDTO response = authService.refresh("gate-refresh", "device-1", AuditContext.none());

        assertThat(jwt.extractRoles(response.getToken())).containsExactly("GATE_SCANNER");
        assertThat(jwt.extractPermissions(response.getToken())).isEmpty();
        assertThat(jwt.extractOrganizerUuid(response.getToken())).isNull();
        assertThat(response.getRefreshToken()).isEqualTo("gate-refresh-2");
    }

    @Test
    @DisplayName("the same account's password login is untouched")
    void passwordLogin_isUnchanged() {
        User user = organizerWhoShops();

        String token = authService.issueToken(user, "browser-1").getToken();

        assertThat(jwt.extractRoles(token)).contains("CUSTOMER", "EVENT_ORGANIZER");
        assertThat(jwt.extractRoles(token)).doesNotContain("GATE_SCANNER");
        assertThat(jwt.extractOrganizerUuid(token)).isEqualTo(user.getUserUuid());
        verify(refreshTokenService).issueNewFamily(eq(user), eq("browser-1"));
    }
}
