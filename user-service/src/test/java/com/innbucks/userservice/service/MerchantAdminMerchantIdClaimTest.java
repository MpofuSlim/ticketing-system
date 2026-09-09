package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.AuthResponseDTO;
import com.innbucks.userservice.dto.LoginRequestDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the {@code merchantId} claim minted for a MERCHANT_ADMIN.
 *
 * <p><b>The bug this closes.</b> marketplace-service scopes a seller
 * <em>exclusively</em> from the JWT's {@code merchantId} claim and refuses a
 * listing without one ({@code 403 merchant_scope_missing}, its
 * {@code ListingService.requireMerchantId}). user-service deliberately minted
 * no such claim for MERCHANT_ADMIN, resolving merchant scope per request from
 * the body instead — so merchant self-service listing was unreachable with a
 * real fleet token, and only the SUPER_ADMIN on-behalf path worked.
 *
 * <p>Pure Mockito — no Spring context, no Docker.
 */
class MerchantAdminMerchantIdClaimTest {

    private static final UUID MERCHANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MERCHANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void merchantAdminOwningOneMerchant_getsItAsTheMerchantIdClaim() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        when(loyalty.merchantIdsForAdmin("owner@rudo.co.zw")).thenReturn(List.of(MERCHANT_A));
        JwtUtil jwt = mock(JwtUtil.class);

        login(merchantAdmin("owner@rudo.co.zw"), loyalty, jwt);

        assertEquals(MERCHANT_A, capturedMerchantId(jwt),
                "the claim marketplace-service scopes a seller by must be minted");
    }

    @Test
    void merchantAdminOwningSEVERAL_getsNoClaim_ratherThanAnArbitraryOne() {
        // The load-bearing decision. A JWT claim is singular and the consumer
        // treats it as authoritative ownership, so picking one of several would
        // silently attribute listings — and therefore commission — to whichever
        // merchant happened to sort first. That error surfaces at invoicing, not
        // at the call. Refusing is the same clean 403 they get today.
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        when(loyalty.merchantIdsForAdmin("owner@rudo.co.zw"))
                .thenReturn(List.of(MERCHANT_A, MERCHANT_B));
        JwtUtil jwt = mock(JwtUtil.class);

        login(merchantAdmin("owner@rudo.co.zw"), loyalty, jwt);

        assertNull(capturedMerchantId(jwt),
                "a multi-merchant admin must get NO claim, never a guessed one");
    }

    @Test
    void merchantAdminOwningNothing_getsNoClaim() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        when(loyalty.merchantIdsForAdmin(anyString())).thenReturn(List.of());
        JwtUtil jwt = mock(JwtUtil.class);

        login(merchantAdmin("nobody@example.com"), loyalty, jwt);

        assertNull(capturedMerchantId(jwt));
    }

    @Test
    void aLoyaltyOutageStillIssuesAToken_justWithoutTheClaim() {
        // merchantIdsForAdmin is best-effort by construction (it swallows 4xx
        // and network errors and returns an empty list), so an outage must
        // degrade to "signed in, merchant calls refused" — never a failed login.
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        when(loyalty.merchantIdsForAdmin(anyString())).thenReturn(List.of());
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.generateToken(anyString(), any(), any(), any(), anyInt(), anyBoolean(),
                any(), any(), any(), any(), any(), any(), anyLong(), any(), any(), any(),
                anyBoolean())).thenReturn("tok");

        AuthResponseDTO resp = login(merchantAdmin("owner@rudo.co.zw"), loyalty, jwt);

        assertEquals("tok", resp.getToken(), "login must survive a loyalty outage");
        assertNull(capturedMerchantId(jwt));
    }

    @Test
    void aRowThatAlreadyCarriesAMerchantId_shortCircuitsTheNetworkCall() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        JwtUtil jwt = mock(JwtUtil.class);
        User user = merchantAdmin("owner@rudo.co.zw");
        user.setLoyaltyMerchantId(MERCHANT_B);

        login(user, loyalty, jwt);

        assertEquals(MERCHANT_B, capturedMerchantId(jwt));
        verify(loyalty, never()).merchantIdsForAdmin(anyString());
    }

    @Test
    void aNonMerchantRoleIsUnaffected_andNeverTriggersTheLookup() {
        // EVENT_ORGANIZER and every other role must keep minting no merchantId,
        // and must not pay the S2S round-trip on their login path.
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        JwtUtil jwt = mock(JwtUtil.class);
        User organizer = User.builder()
                .id(2L)
                .email("org@example.com").password("hashed")
                .roles(User.roleNames(User.Role.EVENT_ORGANIZER))
                .defaultServices(new LinkedHashSet<>(List.of("ticketing")))
                .userUuid(UUID.randomUUID())
                .active(true).mfaEnabled(false).build();

        login(organizer, loyalty, jwt);

        assertNull(capturedMerchantId(jwt));
        verify(loyalty, never()).merchantIdsForAdmin(anyString());
    }

    @Test
    void noLoyaltyClientWired_isANoOp_notACrash() {
        // A plain unit test (or a cell where the bean is absent) must behave
        // exactly as before the feature: no claim, no exception.
        JwtUtil jwt = mock(JwtUtil.class);

        login(merchantAdmin("owner@rudo.co.zw"), null, jwt);

        assertNull(capturedMerchantId(jwt));
    }

    // ---- harness ------------------------------------------------------------

    private static User merchantAdmin(String email) {
        return User.builder()
                .id(1L)
                .email(email).password("hashed")
                .roles(User.roleNames(User.Role.MERCHANT_ADMIN))
                .defaultServices(new LinkedHashSet<>(List.of("loyalty")))
                .userUuid(UUID.randomUUID())
                .active(true).mfaEnabled(false).build();
    }

    private static AuthResponseDTO login(User user, LoyaltyServiceClient loyalty, JwtUtil jwt) {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(userRepo.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(encoder.matches("pw", "hashed")).thenReturn(true);

        AuthService svc = new AuthService(userRepo, mock(TenantProfileRepository.class),
                mock(CustomerProfileRepository.class), encoder, jwt,
                mock(com.innbucks.userservice.service.TokenRevocationService.class),
                mock(com.innbucks.userservice.service.RefreshTokenService.class),
                mock(RefreshTokenRepository.class),
                mock(com.innbucks.userservice.service.AuditService.class));
        ReflectionTestUtils.setField(svc, "maxFailedLoginAttempts", 5);
        ReflectionTestUtils.setField(svc, "lockoutDurationMinutes", 15);
        if (loyalty != null) {
            ReflectionTestUtils.setField(svc, "loyaltyServiceClient", loyalty);
        }

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier(user.getEmail());
        req.setPassword("pw");
        return svc.login(req, null, AuditContext.none());
    }

    /** The 8th positional arg of generateToken is the merchantId claim. */
    private static UUID capturedMerchantId(JwtUtil jwt) {
        ArgumentCaptor<UUID> merchantId = ArgumentCaptor.forClass(UUID.class);
        verify(jwt).generateToken(anyString(), any(), any(), any(), anyInt(), anyBoolean(),
                any(), merchantId.capture(), any(), any(), any(), any(), anyLong(), any(),
                any(), any(), anyBoolean());
        return merchantId.getValue();
    }
}
