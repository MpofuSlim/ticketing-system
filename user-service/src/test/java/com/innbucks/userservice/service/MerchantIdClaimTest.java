package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.LoginRequestDTO;
import com.innbucks.userservice.entity.User;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins which accounts carry the loyalty {@code merchantId} / {@code shopId}
 * scope claims.
 *
 * <p><b>A MERCHANT_ADMIN carries none.</b> A business is scoped by its
 * organization ({@code orgId}), and loyalty and marketplace read that. The
 * claim used to be resolved at every merchant-admin login by asking loyalty for
 * the merchants bound to the admin's email — so login depended on loyalty, and
 * an admin running two merchants got no claim and no marketplace access at all.
 * The lookup no longer exists (AuthService holds no loyalty client), so there is
 * nothing here to stub: the assertion is simply that the claim is absent.
 *
 * <p><b>Shop staff keep theirs.</b> SHOP_ADMIN / SHOP_USER are pinned to one
 * shop of one merchant, stamped on their row by ShopStaffService, and loyalty
 * reads those claims as their scope. No network call is involved.
 *
 * <p>Pure Mockito — no Spring context, no Docker.
 */
class MerchantIdClaimTest {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SHOP = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void merchantAdmin_getsNoMerchantIdClaim() {
        JwtUtil jwt = mock(JwtUtil.class);

        login(account("owner@rudo.co.zw", User.Role.MERCHANT_ADMIN), jwt);

        assertNull(captured(jwt).merchantId(),
                "a business is scoped by orgId now; a merchantId claim would be a second, stale scope");
        assertNull(captured(jwt).shopId());
    }

    @Test
    void merchantAdmin_withAStampedRow_stillGetsNoClaim() {
        // Only shop staff are stamped in practice; a stray stamp on a merchant
        // admin's row must not resurrect the old claim.
        JwtUtil jwt = mock(JwtUtil.class);
        User admin = account("owner@rudo.co.zw", User.Role.MERCHANT_ADMIN);
        admin.setLoyaltyMerchantId(MERCHANT);

        login(admin, jwt);

        assertNull(captured(jwt).merchantId());
    }

    @Test
    void shopAdmin_keepsTheMerchantAndShopStampedOnTheirRow() {
        JwtUtil jwt = mock(JwtUtil.class);
        User shopAdmin = account("shop@rudo.co.zw", User.Role.SHOP_ADMIN);
        shopAdmin.setLoyaltyMerchantId(MERCHANT);
        shopAdmin.setLoyaltyShopId(SHOP);

        login(shopAdmin, jwt);

        assertEquals(MERCHANT, captured(jwt).merchantId());
        assertEquals(SHOP, captured(jwt).shopId());
    }

    @Test
    void organizer_getsNoMerchantScope() {
        JwtUtil jwt = mock(JwtUtil.class);

        login(account("org@example.com", User.Role.EVENT_ORGANIZER), jwt);

        assertNull(captured(jwt).merchantId());
        assertNull(captured(jwt).shopId());
    }

    // ---- harness ------------------------------------------------------------

    private record Scope(UUID merchantId, UUID shopId) {}

    private static User account(String email, User.Role role) {
        return User.builder()
                .id(1L)
                .email(email).password("hashed")
                .roles(User.roleNames(role))
                .defaultServices(new LinkedHashSet<>(List.of("loyalty")))
                .userUuid(UUID.randomUUID())
                .active(true).mfaEnabled(false).build();
    }

    private static void login(User user, JwtUtil jwt) {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(userRepo.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(encoder.matches("pw", "hashed")).thenReturn(true);

        AuthService svc = new AuthService(userRepo, mock(TenantProfileRepository.class),
                mock(CustomerProfileRepository.class), encoder, jwt,
                mock(TokenRevocationService.class),
                mock(RefreshTokenService.class),
                mock(RefreshTokenRepository.class),
                mock(AuditService.class));
        ReflectionTestUtils.setField(svc, "maxFailedLoginAttempts", 5);
        ReflectionTestUtils.setField(svc, "lockoutDurationMinutes", 15);

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier(user.getEmail());
        req.setPassword("pw");
        svc.login(req, null, AuditContext.none());
    }

    /** generateToken's 8th and 9th positional args are the merchantId and shopId claims. */
    private static Scope captured(JwtUtil jwt) {
        ArgumentCaptor<UUID> merchantId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> shopId = ArgumentCaptor.forClass(UUID.class);
        verify(jwt).generateToken(anyString(), any(), any(), any(), anyInt(), anyBoolean(),
                any(), merchantId.capture(), shopId.capture(), any(), any(), any(), anyLong(), any(),
                any(), any(), anyBoolean(), any());
        return new Scope(merchantId.getValue(), shopId.getValue());
    }
}
