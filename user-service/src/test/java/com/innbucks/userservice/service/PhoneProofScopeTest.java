package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.AuthResponseDTO;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a PHONE PROOF is allowed to become.
 *
 * <p>A merchant admin may legitimately shop on the super app with the very
 * number their shop runs on — the operator's own requirement, and an ordinary
 * thing for a shopkeeper to do. So {@code /auth/exchange} must let that account
 * in. What it must NOT do is hand back the merchant role, because the assertion
 * proves one thing — whoever holds that number authenticated at the middleware —
 * and it never passed the MFA challenge {@code MfaPolicy.required} demands of a
 * system user on the password path. Left unscoped, phone possession alone would
 * reach every merchant surface in the fleet, marketplace's payout destination
 * among them.
 *
 * <p>These are the assertions that make "only ever a CUSTOMER" true rather than
 * merely documented. Before the scoping existed, the dual-role cases below all
 * returned a MERCHANT_ADMIN session.
 */
class PhoneProofScopeTest {

    private static final String PHONE = "+263771234567";

    private UserRepository userRepo;
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

        userRepo = mock(UserRepository.class);
        customerRepo = mock(CustomerProfileRepository.class);
        refreshTokenService = mock(RefreshTokenService.class);
        when(refreshTokenService.issueNewFamily(any(User.class), any())).thenReturn("refresh");
        when(refreshTokenService.issueNewFamily(any(User.class), any(), anyBoolean())).thenReturn("refresh");

        authService = new AuthService(userRepo, mock(TenantProfileRepository.class), customerRepo,
                mock(PasswordEncoder.class), jwt,
                mock(TokenRevocationService.class), refreshTokenService,
                mock(RefreshTokenRepository.class), mock(AuditService.class));
    }

    /** The shopkeeper who also shops: one account, one number, both roles. */
    private User dualRole() {
        User user = User.builder()
                .id(11L).userUuid(UUID.randomUUID()).phoneNumber(PHONE)
                .email("rudo@example.com")
                .roles(User.roleNames(User.Role.CUSTOMER, User.Role.MERCHANT_ADMIN))
                .loyaltyMerchantId(UUID.randomUUID())
                .active(true).approved(true).password("x").tokenVersion(1L).build();
        when(customerRepo.findByUserId(11L)).thenReturn(Optional.of(
                CustomerProfile.builder().registrationTier(1).build()));
        return user;
    }

    private java.util.List<String> roles(String token) {
        return jwt.extractRoles(token);
    }

    @Test
    @DisplayName("a phone proof yields CUSTOMER only, even when the account is also a merchant admin")
    void phoneProof_scopesRolesToCustomer() {
        User user = dualRole();

        AuthResponseDTO response = authService.issuePhoneProofToken(user, "device-1");

        assertThat(roles(response.getToken())).containsExactly("CUSTOMER");
        // The DTO the app reads must agree with the token it carries — a client
        // that renders a seller menu off response.roles while the token refuses
        // every seller call is its own kind of broken.
        assertThat(response.getRoles()).containsExactly("CUSTOMER");
    }

    @Test
    @DisplayName("the merchant SCOPE claim is withheld too, not just the role")
    void phoneProof_withholdsMerchantScope() {
        User user = dualRole();

        String token = authService.issuePhoneProofToken(user, "device-1").getToken();

        // marketplace reads merchantId as authoritative ownership. A claim naming
        // authority the roles no longer grant is strictly worse than no claim —
        // it waits for the first consumer that trusts the claim on its own.
        assertThat(jwt.extractMerchantId(token)).isNull();
        assertThat(jwt.extractShopId(token)).isNull();
        assertThat(jwt.extractOrganizerUuid(token)).isNull();
    }

    @Test
    @DisplayName("the SAME account still gets its full role set from a password login")
    void passwordLogin_isUnchanged() {
        User user = dualRole();

        String token = authService.issueToken(user, "device-1").getToken();

        // The account is untouched by the scoping — only the phone-proof PATH is
        // narrowed. The shopkeeper signs in to the admin portal exactly as
        // before, password plus MFA, and sells.
        assertThat(roles(token)).contains("CUSTOMER", "MERCHANT_ADMIN");
        // Their business scope is the organization (orgId — OrganizationClaimsTest),
        // not a loyalty merchantId: that claim is no longer minted for a merchant
        // admin on ANY path, password included.
        assertThat(jwt.extractMerchantId(token)).isNull();
    }

    @Test
    @DisplayName("permissions follow the narrowed roles, not the account's")
    void phoneProof_permissionsFollowTheScopedRoles() {
        User user = dualRole();

        String token = authService.issuePhoneProofToken(user, "device-1").getToken();

        // perms are resolved from roleNames at the mint, so narrowing the roles
        // narrows these for free — but only while that ordering holds, which is
        // what this pins.
        java.util.List<String> perms = jwt.extractPermissions(token);
        assertThat(perms).noneMatch(p -> p.startsWith("listings:"));
        assertThat(perms).noneMatch(p -> p.startsWith("settlements:"));
    }

    @Test
    @DisplayName("the refresh family is marked phone-proof, so the scope can survive rotation")
    void phoneProof_marksTheRefreshFamily() {
        User user = dualRole();

        authService.issuePhoneProofToken(user, "device-1");

        // Without this flag on the ROW, /auth/refresh — which re-reads the live
        // user — would re-inflate the session to every role the account holds on
        // its very first rotation, and the scoping would last 15 minutes.
        verify(refreshTokenService).issueNewFamily(user, "device-1", true);
    }

    @Test
    @DisplayName("an ordinary login does NOT mark the family")
    void passwordLogin_doesNotMarkTheFamily() {
        User user = dualRole();

        authService.issueToken(user, "device-1");

        verify(refreshTokenService).issueNewFamily(eq(user), eq("device-1"));
    }

    @Test
    @DisplayName("a customer-only account is unaffected by the narrowing")
    void plainCustomer_isUnchanged() {
        User user = User.builder()
                .id(12L).userUuid(UUID.randomUUID()).phoneNumber(PHONE)
                .roles(User.roleNames(User.Role.CUSTOMER))
                .active(true).approved(true).password("x").tokenVersion(1L).build();
        when(customerRepo.findByUserId(12L)).thenReturn(Optional.of(
                CustomerProfile.builder().registrationTier(1).build()));

        String token = authService.issuePhoneProofToken(user, null).getToken();

        assertThat(roles(token)).containsExactly("CUSTOMER");
        assertThat(jwt.extractPhoneNumber(token)).isEqualTo(PHONE);
    }
}
