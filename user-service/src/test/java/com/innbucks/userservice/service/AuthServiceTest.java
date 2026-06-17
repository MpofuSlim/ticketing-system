package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.TenantProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    /** Lockout threshold the test profile pins — matches application.yaml default. */
    private static final int LOCKOUT_THRESHOLD = 7;
    /** Lockout duration the test profile pins — matches application.yaml default. */
    private static final int LOCKOUT_MINUTES = 30;

    /**
     * Inject the {@code @Value}-driven lockout config. Without this the
     * primitive defaults (0) would lock every account on its first
     * failed attempt, breaking every existing wrong-pw test.
     */
    private static AuthService withLockoutConfig(AuthService svc) {
        org.springframework.test.util.ReflectionTestUtils.setField(
                svc, "maxFailedLoginAttempts", LOCKOUT_THRESHOLD);
        org.springframework.test.util.ReflectionTestUtils.setField(
                svc, "lockoutDurationMinutes", LOCKOUT_MINUTES);
        return svc;
    }

    private AuthService newService(UserRepository userRepo,
                                   TenantProfileRepository tenantRepo,
                                   PasswordEncoder encoder,
                                   JwtUtil jwt) {
        return withLockoutConfig(new AuthService(userRepo, tenantRepo,
                mock(CustomerProfileRepository.class), encoder, jwt,
                mock(TokenRevocationService.class),
                mock(RefreshTokenService.class),
                mock(RefreshTokenRepository.class),
                mock(AuditService.class)));
    }

    private AuthService newService(UserRepository userRepo,
                                   TenantProfileRepository tenantRepo,
                                   CustomerProfileRepository customerRepo,
                                   PasswordEncoder encoder,
                                   JwtUtil jwt) {
        return withLockoutConfig(new AuthService(userRepo, tenantRepo, customerRepo, encoder, jwt,
                mock(TokenRevocationService.class),
                mock(RefreshTokenService.class),
                mock(RefreshTokenRepository.class),
                mock(AuditService.class)));
    }

    /**
     * Overload that lets a test pass an explicit {@link RefreshTokenRepository}
     * mock so it can verify the bulk-revoke call that /auth/login fires for
     * single-active-session enforcement.
     */
    private AuthService newService(UserRepository userRepo,
                                   TenantProfileRepository tenantRepo,
                                   CustomerProfileRepository customerRepo,
                                   PasswordEncoder encoder,
                                   JwtUtil jwt,
                                   RefreshTokenRepository refreshRepo) {
        return withLockoutConfig(new AuthService(userRepo, tenantRepo, customerRepo, encoder, jwt,
                mock(TokenRevocationService.class),
                mock(RefreshTokenService.class),
                refreshRepo,
                mock(AuditService.class)));
    }

    private RegisterRequestDTO baseRequest(String email, String phone, String... bundles) {
        RegisterRequestDTO req = new RegisterRequestDTO();
        req.setFirstName("Jane");
        req.setMiddleName("M");
        req.setLastName("Doe");
        req.setPhoneNumber(phone);
        req.setEmail(email);
        req.setCountry("Zimbabwe");
        req.setDefaultServices(List.of(bundles));
        return req;
    }

    @Test
    void register_loyaltyBundle_assignsMerchantAdminRole_pendingApproval() {
        UserRepository userRepo = mock(UserRepository.class);
        TenantProfileRepository tenantRepo = mock(TenantProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(userRepo.existsByEmail(any())).thenReturn(false);
        when(userRepo.existsByPhoneNumber(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hashed");

        RegisterRequestDTO req = baseRequest("ma@example.com", "0777000001", "loyalty");

        AuthResponseDTO response = newService(userRepo, tenantRepo, encoder, jwt).register(req);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(saved.capture());
        // No password is collected at registration — a placeholder hash is stored,
        // and the account is created inactive + unapproved (pending SUPER_ADMIN).
        assertEquals("hashed", saved.getValue().getPassword());
        assertFalse(saved.getValue().isActive());
        assertFalse(saved.getValue().isApproved());
        assertTrue(saved.getValue().getRoles().contains(User.Role.MERCHANT_ADMIN));
        // Bundle list (not the expanded microservices) is what we store and surface
        assertEquals(new LinkedHashSet<>(List.of("loyalty")), saved.getValue().getDefaultServices());
        // Tenant profile is created only for business accounts (isBusiness=true).
        verify(tenantRepo, never()).save(any());
        assertEquals(List.of("MERCHANT_ADMIN"), response.getRoles());
        assertEquals(List.of("loyalty"), response.getDefaultServices());
        assertFalse(response.isMustChangePassword());
    }

    @Test
    void register_ticketingBundle_assignsEventOrganizerRole_pendingApproval() {
        UserRepository userRepo = mock(UserRepository.class);
        TenantProfileRepository tenantRepo = mock(TenantProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(userRepo.existsByEmail(any())).thenReturn(false);
        when(userRepo.existsByPhoneNumber(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hashed");

        RegisterRequestDTO req = baseRequest("eo@example.com", "0777111122", "ticketing");

        AuthResponseDTO response = newService(userRepo, tenantRepo, encoder, jwt).register(req);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(saved.capture());
        assertTrue(saved.getValue().getRoles().contains(User.Role.EVENT_ORGANIZER));
        assertEquals(List.of("ticketing"), response.getDefaultServices());
        // No tenant profile for a non-business registration.
        verify(tenantRepo, never()).save(any());
    }

    @Test
    void register_bothBundles_grantsBothRoles() {
        UserRepository userRepo = mock(UserRepository.class);
        TenantProfileRepository tenantRepo = mock(TenantProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(userRepo.existsByEmail(any())).thenReturn(false);
        when(userRepo.existsByPhoneNumber(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hashed");

        RegisterRequestDTO req = baseRequest("multi@example.com", "0777999999", "ticketing", "loyalty");

        AuthResponseDTO response = newService(userRepo, tenantRepo, encoder, jwt).register(req);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(saved.capture());
        assertTrue(saved.getValue().getRoles().contains(User.Role.EVENT_ORGANIZER));
        assertTrue(saved.getValue().getRoles().contains(User.Role.MERCHANT_ADMIN));
        // Stored bundles, not the expanded set
        assertEquals(new LinkedHashSet<>(List.of("ticketing", "loyalty")),
                saved.getValue().getDefaultServices());
        verify(tenantRepo, never()).save(any());
        assertTrue(response.getRoles().contains("EVENT_ORGANIZER"));
        assertTrue(response.getRoles().contains("MERCHANT_ADMIN"));
        assertTrue(response.getDefaultServices().contains("ticketing"));
        assertTrue(response.getDefaultServices().contains("loyalty"));
    }

    @Test
    void register_businessAccount_createsTenantProfileWithBpoNumber() {
        UserRepository userRepo = mock(UserRepository.class);
        TenantProfileRepository tenantRepo = mock(TenantProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(userRepo.existsByEmail(any())).thenReturn(false);
        when(userRepo.existsByPhoneNumber(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hashed");

        RegisterRequestDTO req = baseRequest("biz@example.com", "0777333333", "ticketing");
        req.setBusiness(true);
        req.setBusinessName("InnBucks Ticketing Ltd");
        req.setBusinessAddress("123 Samora Machel Ave, Harare");
        req.setBusinessEmail("accounts@innbucks.co.zw");
        req.setBpoNumber("12345");

        newService(userRepo, tenantRepo, encoder, jwt).register(req);

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(savedUser.capture());
        assertTrue(savedUser.getValue().isBusiness());
        assertEquals("Zimbabwe", savedUser.getValue().getCountry());

        ArgumentCaptor<TenantProfile> savedProfile = ArgumentCaptor.forClass(TenantProfile.class);
        verify(tenantRepo).save(savedProfile.capture());
        assertEquals("InnBucks Ticketing Ltd", savedProfile.getValue().getBusinessName());
        assertEquals("123 Samora Machel Ave, Harare", savedProfile.getValue().getBusinessAddress());
        // businessEmail now captured at registration so it surfaces as the
        // organizer's email on event listings (was always null before).
        assertEquals("accounts@innbucks.co.zw", savedProfile.getValue().getBusinessEmail());
        assertEquals("12345", savedProfile.getValue().getBpoNumber());
    }

    @Test
    void register_businessAccount_businessEmailOptional_persistsNullWhenOmitted() {
        UserRepository userRepo = mock(UserRepository.class);
        TenantProfileRepository tenantRepo = mock(TenantProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(userRepo.existsByEmail(any())).thenReturn(false);
        when(userRepo.existsByPhoneNumber(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hashed");

        // Business account with the required fields but NO businessEmail — it's
        // optional, so registration must succeed and the profile carries null.
        RegisterRequestDTO req = baseRequest("biz2@example.com", "0777444444", "ticketing");
        req.setBusiness(true);
        req.setBusinessName("No Email Co");
        req.setBusinessAddress("1 Nowhere St, Harare");
        req.setBpoNumber("67890");

        newService(userRepo, tenantRepo, encoder, jwt).register(req);

        ArgumentCaptor<TenantProfile> savedProfile = ArgumentCaptor.forClass(TenantProfile.class);
        verify(tenantRepo).save(savedProfile.capture());
        assertEquals("No Email Co", savedProfile.getValue().getBusinessName());
        assertNull(savedProfile.getValue().getBusinessEmail());
    }

    @Test
    void register_rejectsUnknownBundle() {
        UserRepository userRepo = mock(UserRepository.class);
        AuthService service = newService(userRepo, mock(TenantProfileRepository.class),
                mock(PasswordEncoder.class), mock(JwtUtil.class));

        RegisterRequestDTO req = baseRequest("c@example.com", "0777222222", "not-a-bundle");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.register(req));
        assertTrue(ex.getMessage().toLowerCase().contains("unknown service bundle"));
        verify(userRepo, never()).save(any());
    }

    @Test
    void register_rejectsDuplicateEmail() {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.existsByEmail("dup@example.com")).thenReturn(true);
        AuthService service = newService(userRepo, mock(TenantProfileRepository.class),
                mock(PasswordEncoder.class), mock(JwtUtil.class));

        RegisterRequestDTO req = baseRequest("dup@example.com", "0777000002", "loyalty");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.register(req));
        assertEquals("Email already registered", ex.getMessage());
        verify(userRepo, never()).save(any());
    }

    @Test
    void login_withValidEmail_issuesToken_andExpandsServicesInJwt() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        User user = User.builder()
                .id(1L)
                .email("u@example.com").password("hashed")
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN))
                .defaultServices(new LinkedHashSet<>(List.of("loyalty")))
                .active(true).mfaEnabled(false).build();
        when(userRepo.findByEmail("u@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("pw", "hashed")).thenReturn(true);
        // The JWT services claim should be the expanded microservices for the loyalty bundle.
        // MERCHANT_ADMIN — JwtUtil emits no name claims for staff roles.
        when(jwt.generateToken(eq("u@example.com"), eq(List.of("MERCHANT_ADMIN")),
                eq(List.of("loyalty", "payments")), eq(4), eq(true), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), anyLong(), isNull(), any(), any(), anyBoolean())).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("u@example.com"); req.setPassword("pw");

        AuthResponseDTO resp = newService(userRepo, mock(TenantProfileRepository.class),
                encoder, jwt).login(req, null, com.innbucks.userservice.service.AuditContext.none());

        assertEquals("tok", resp.getToken());
        assertEquals(List.of("MERCHANT_ADMIN"), resp.getRoles());
        // Response surfaces the bundle, not the expanded services
        assertEquals(List.of("loyalty"), resp.getDefaultServices());
    }

    @Test
    void login_superAdmin_jwtCarriesAllMicroservices() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        User user = User.builder()
                .id(1L)
                .email("admin@innbucks.co.zw").password("hashed")
                .roles(EnumSet.of(User.Role.SUPER_ADMIN))
                .defaultServices(new LinkedHashSet<>(List.of("ticketing", "loyalty")))
                .active(true).build();
        when(userRepo.findByEmail("admin@innbucks.co.zw")).thenReturn(Optional.of(user));
        when(encoder.matches("pw", "hashed")).thenReturn(true);
        // This superadmin has no stored country, so the JWT country claim
        // defaults to Zimbabwe (see login_superAdminWithoutCountry_* below).
        when(jwt.generateToken(any(), any(), any(), anyInt(), anyBoolean(), isNull(), isNull(), isNull(),
                any(), any(), any(), anyLong(), eq("Zimbabwe"), any(), any(), anyBoolean())).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("admin@innbucks.co.zw"); req.setPassword("pw");

        AuthResponseDTO resp = newService(userRepo, mock(TenantProfileRepository.class),
                encoder, jwt).login(req, null, com.innbucks.userservice.service.AuditContext.none());

        assertEquals(List.of("SUPER_ADMIN"), resp.getRoles());
        assertEquals(List.of("ticketing", "loyalty"), resp.getDefaultServices());

        // Verify the JWT was issued with the expanded set covering every microservice.
        ArgumentCaptor<List<String>> servicesCaptor = ArgumentCaptor.forClass(List.class);
        verify(jwt).generateToken(any(), any(), servicesCaptor.capture(), anyInt(), anyBoolean(), isNull(), isNull(), isNull(),
                any(), any(), any(), anyLong(), eq("Zimbabwe"), any(), any(), anyBoolean());
        List<String> services = servicesCaptor.getValue();
        assertTrue(services.contains("events"));
        assertTrue(services.contains("seats"));
        assertTrue(services.contains("bookings"));
        assertTrue(services.contains("payments"));
        assertTrue(services.contains("loyalty"));
    }

    @Test
    void login_superAdminWithoutCountry_defaultsCountryClaimToZimbabwe() {
        // admin@innbucks.co.zw is seeded with no country. event-service's
        // createEvent rejects a token that carries no country claim, so the
        // superadmin must default to Zimbabwe rather than mint a country-less
        // token that fails downstream.
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        User user = User.builder()
                .id(1L)
                .email("admin@innbucks.co.zw").password("hashed")
                .roles(EnumSet.of(User.Role.SUPER_ADMIN))
                .active(true).build(); // no country stored
        when(userRepo.findByEmail("admin@innbucks.co.zw")).thenReturn(Optional.of(user));
        when(encoder.matches("pw", "hashed")).thenReturn(true);
        when(jwt.generateToken(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(),
                any(), any(), any(), anyLong(), any(), any(), any(), anyBoolean())).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("admin@innbucks.co.zw"); req.setPassword("pw");

        newService(userRepo, mock(TenantProfileRepository.class), encoder, jwt)
                .login(req, null, com.innbucks.userservice.service.AuditContext.none());

        ArgumentCaptor<String> countryCaptor = ArgumentCaptor.forClass(String.class);
        verify(jwt).generateToken(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(),
                any(), any(), any(), anyLong(), countryCaptor.capture(), any(), any(), anyBoolean());
        assertEquals("Zimbabwe", countryCaptor.getValue(),
                "SUPER_ADMIN with no stored country must default the JWT country claim to Zimbabwe");
    }

    @Test
    void login_superAdminWithExplicitCountry_keepsItsOwnCountry() {
        // The default only fills a gap — a superadmin that already carries a
        // country keeps it; we don't clobber real data with the fallback.
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        User user = User.builder()
                .id(2L)
                .email("admin-ke@innbucks.co.ke").password("hashed")
                .roles(EnumSet.of(User.Role.SUPER_ADMIN))
                .country("Kenya")
                .active(true).build();
        when(userRepo.findByEmail("admin-ke@innbucks.co.ke")).thenReturn(Optional.of(user));
        when(encoder.matches("pw", "hashed")).thenReturn(true);
        when(jwt.generateToken(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(),
                any(), any(), any(), anyLong(), any(), any(), any(), anyBoolean())).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("admin-ke@innbucks.co.ke"); req.setPassword("pw");

        newService(userRepo, mock(TenantProfileRepository.class), encoder, jwt)
                .login(req, null, com.innbucks.userservice.service.AuditContext.none());

        ArgumentCaptor<String> countryCaptor = ArgumentCaptor.forClass(String.class);
        verify(jwt).generateToken(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(),
                any(), any(), any(), anyLong(), countryCaptor.capture(), any(), any(), anyBoolean());
        assertEquals("Kenya", countryCaptor.getValue(),
                "An explicit country on a SUPER_ADMIN must NOT be overridden by the Zimbabwe default");
    }

    @Test
    void login_withValidPhoneNumber_issuesTokenWithCustomerTier() {
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository customerRepo = mock(CustomerProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        User user = User.builder()
                .id(7L)
                .phoneNumber("0777000099").password("hashed")
                .roles(EnumSet.of(User.Role.CUSTOMER))
                .active(true).mfaEnabled(false).build();
        CustomerProfile profile = CustomerProfile.builder()
                .user(user).registrationTier(2).verified(false).build();
        when(userRepo.findByPhoneNumber("0777000099")).thenReturn(Optional.of(user));
        when(customerRepo.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(encoder.matches("pw", "hashed")).thenReturn(true);
        when(jwt.generateToken(eq("0777000099"), eq(List.of("CUSTOMER")),
                any(), eq(2), eq(false), eq("0777000099"), isNull(), isNull(),
                any(), any(), any(), anyLong(), isNull(), any(), any(), anyBoolean())).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("0777000099"); req.setPassword("pw");

        AuthResponseDTO resp = newService(userRepo, mock(TenantProfileRepository.class),
                customerRepo, encoder, jwt).login(req, null, com.innbucks.userservice.service.AuditContext.none());

        assertEquals("tok", resp.getToken());
        assertEquals(List.of("CUSTOMER"), resp.getRoles());
        assertEquals(2, resp.getTier());
        assertEquals(Boolean.FALSE, resp.getVerified());
    }

    @Test
    void login_withWrongPassword_throws() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        User user = User.builder().email("u@example.com").password("hashed")
                .roles(EnumSet.of(User.Role.CUSTOMER)).build();
        when(userRepo.findByEmail("u@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches(any(), any())).thenReturn(false);

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("u@example.com"); req.setPassword("wrong");

        AuthService service = newService(userRepo, mock(TenantProfileRepository.class),
                encoder, mock(JwtUtil.class));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.login(req, null, com.innbucks.userservice.service.AuditContext.none()));
        assertEquals("Invalid credentials", ex.getMessage());
    }

    @Test
    void login_withInactiveAccount_throws() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        User user = User.builder().email("u@example.com").password("hashed")
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN))
                .active(false).build();
        when(userRepo.findByEmail("u@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches(any(), any())).thenReturn(true);

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("u@example.com"); req.setPassword("pw");

        AuthService service = newService(userRepo, mock(TenantProfileRepository.class),
                encoder, mock(JwtUtil.class));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.login(req, null, com.innbucks.userservice.service.AuditContext.none()));
        assertTrue(ex.getMessage().toLowerCase().contains("not active"));
    }

    @Test
    void login_withUnknownEmail_throws() {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findByEmail(any())).thenReturn(Optional.empty());

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("missing@example.com"); req.setPassword("pw");

        AuthService service = newService(userRepo, mock(TenantProfileRepository.class),
                mock(PasswordEncoder.class), mock(JwtUtil.class));
        assertThrows(RuntimeException.class, () -> service.login(req, null, com.innbucks.userservice.service.AuditContext.none()));
    }

    @Test
    void login_withMfaEnabled_stillIssuesTokenDirectly() {
        // MFA-at-login is no longer enforced; the mfaEnabled flag doesn't block token issuance.
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository customerRepo = mock(CustomerProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        User user = User.builder().id(1L).email("u@example.com").password("hashed")
                .roles(EnumSet.of(User.Role.CUSTOMER)).active(true).mfaEnabled(true).build();
        CustomerProfile profile = CustomerProfile.builder()
                .user(user).registrationTier(1).verified(false).build();
        when(userRepo.findByEmail(any())).thenReturn(Optional.of(user));
        when(customerRepo.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(encoder.matches(any(), any())).thenReturn(true);
        when(jwt.generateToken(eq("u@example.com"), eq(List.of("CUSTOMER")),
                any(), anyInt(), anyBoolean(), isNull(), isNull(), isNull(),
                any(), any(), any(), anyLong(), isNull(), any(), any(), anyBoolean())).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("u@example.com"); req.setPassword("pw");

        AuthResponseDTO resp = newService(userRepo, mock(TenantProfileRepository.class),
                customerRepo, encoder, jwt).login(req, null, com.innbucks.userservice.service.AuditContext.none());

        assertEquals("tok", resp.getToken());
        assertFalse(resp.isMfaRequired());
    }

    @Test
    void login_bumpsTokenVersion_andRevokesAllPriorRefreshTokens() {
        // Single-active-session contract: a fresh login must invalidate
        // every previously-issued token for this user. The mechanism is
        // (1) increment users.token_version (every prior access token's
        // tokenVersion claim now mismatches, JwtFilter rejects them),
        // and (2) bulk-revoke the user's still-live refresh-token rows
        // (the previous device can't extend its session via /auth/refresh).
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        RefreshTokenRepository refreshRepo = mock(RefreshTokenRepository.class);
        when(refreshRepo.revokeAllForUser(anyLong(), any())).thenReturn(2);

        User user = User.builder()
                .id(42L)
                .email("u@example.com")
                .firstName("U").lastName("U")
                .phoneNumber("0777000042")
                .password("hashed")
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN))
                .defaultServices(new LinkedHashSet<>(List.of("loyalty")))
                .active(true)
                .tokenVersion(7L) // already had 7 prior sessions
                .build();
        when(userRepo.findByEmail("u@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("pw", "hashed")).thenReturn(true);
        when(jwt.generateToken(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(),
                any(), any(), any(), anyLong(), isNull(), any(), any(), anyBoolean())).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("u@example.com"); req.setPassword("pw");

        newService(userRepo, mock(TenantProfileRepository.class),
                mock(CustomerProfileRepository.class), encoder, jwt, refreshRepo).login(req, null, com.innbucks.userservice.service.AuditContext.none());

        assertEquals(8L, user.getTokenVersion(), "login must bump token_version by 1");
        verify(userRepo).save(user);
        verify(refreshRepo).revokeAllForUser(eq(42L), any());

        // The fresh access token must carry the BUMPED version (8), not the
        // pre-login one (7) — otherwise the very token we just issued would
        // be rejected by JwtFilter on the next request.
        ArgumentCaptor<Long> versionCaptor = ArgumentCaptor.forClass(Long.class);
        verify(jwt).generateToken(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(),
                any(), any(), any(), versionCaptor.capture(), isNull(), any(), any(), anyBoolean());
        assertEquals(8L, versionCaptor.getValue());
    }

    // ----- Failed-login lockout -----

    private User aliceWithAttempts(int attempts, java.time.Instant lockedUntil) {
        return User.builder()
                .id(101L)
                .email("alice@example.com")
                .password("hashed")
                .roles(EnumSet.of(User.Role.CUSTOMER))
                .active(true)
                .failedLoginAttempts(attempts)
                .lockedUntil(lockedUntil)
                .build();
    }

    private static LoginRequestDTO loginReq(String pw) {
        LoginRequestDTO r = new LoginRequestDTO();
        r.setIdentifier("alice@example.com");
        r.setPassword(pw);
        return r;
    }

    @Test
    void login_wrongPassword_incrementsFailedAttempts_butStaysBelowThreshold() {
        // Sixth strike (threshold is 7). Counter goes to 6; lockedUntil
        // stays null because we haven't crossed the line yet.
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        User user = aliceWithAttempts(5, null);
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("wrong", "hashed")).thenReturn(false);

        AuthService svc = newService(userRepo, mock(TenantProfileRepository.class),
                encoder, mock(JwtUtil.class));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> svc.login(loginReq("wrong"), null, com.innbucks.userservice.service.AuditContext.none()));

        // Still 400 — DIFFERENT exception type from AccountLockedException.
        assertEquals("Invalid credentials", ex.getMessage());
        assertFalse(ex instanceof AuthService.AccountLockedException,
                "Below-threshold failures must NOT throw the 423-mapped AccountLockedException");
        assertEquals(6, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil(),
                "lockedUntil must stay null while attempts < threshold");
        verify(userRepo).save(user);
    }

    @Test
    void login_wrongPasswordAtThreshold_locksAccount_butStillReturns400() {
        // Seventh strike → counter reaches threshold → lockedUntil
        // stamped. CRITICAL: still throws "Invalid credentials" (400),
        // NOT AccountLockedException (423). Otherwise the lockout-
        // transition response shape leaks identifier existence — an
        // attacker would know the account is real because they just
        // saw a 423.
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        User user = aliceWithAttempts(6, null);
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches(any(), any())).thenReturn(false);

        AuthService svc = newService(userRepo, mock(TenantProfileRepository.class),
                encoder, mock(JwtUtil.class));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> svc.login(loginReq("wrong"), null, com.innbucks.userservice.service.AuditContext.none()));

        assertEquals("Invalid credentials", ex.getMessage(),
                "The lockout-triggering attempt MUST return the same 400 shape as wrong-pw " +
                        "on a nonexistent identifier — otherwise the response transition becomes " +
                        "an oracle for identifier enumeration.");
        assertFalse(ex instanceof AuthService.AccountLockedException);

        assertEquals(7, user.getFailedLoginAttempts());
        assertNotNull(user.getLockedUntil(),
                "lockedUntil must be stamped on the threshold-crossing attempt");
        assertTrue(user.getLockedUntil().isAfter(java.time.Instant.now()),
                "lockedUntil must be in the future");
        verify(userRepo).save(user);
    }

    @Test
    void login_throws423_whenAccountIsCurrentlyLocked_evenWithCorrectPassword() {
        // Correct password against a locked account must still 423.
        // Otherwise an attacker who lucky-guesses during the lockout
        // window sneaks in.
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        java.time.Instant lockedUntil = java.time.Instant.now().plus(java.time.Duration.ofMinutes(20));
        User user = aliceWithAttempts(7, lockedUntil);
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches(any(), any())).thenReturn(true);  // would have passed!

        AuthService svc = newService(userRepo, mock(TenantProfileRepository.class),
                encoder, mock(JwtUtil.class));
        AuthService.AccountLockedException ex = assertThrows(
                AuthService.AccountLockedException.class,
                () -> svc.login(loginReq("right"), null, com.innbucks.userservice.service.AuditContext.none()));
        assertEquals(lockedUntil, ex.getLockedUntil(),
                "423 response must carry the deadline the FE renders as a countdown");

        // Password check must NOT have run (short-circuit before it).
        verify(encoder, never()).matches(any(), any());
        // No state mutation — counter / lockout stay as-is.
        verify(userRepo, never()).save(any());
        assertEquals(7, user.getFailedLoginAttempts());
        assertEquals(lockedUntil, user.getLockedUntil());
    }

    @Test
    void login_throws423_whenAccountIsCurrentlyLocked_withWrongPassword() {
        // Wrong pw against a locked account: 423, NO counter increment,
        // NO write. The wrong attempt doesn't extend the lockout —
        // extending on lock-and-locked attempts would let an attacker
        // refresh the lockout forever and lock a victim out indefinitely.
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        java.time.Instant lockedUntil = java.time.Instant.now().plus(java.time.Duration.ofMinutes(20));
        User user = aliceWithAttempts(7, lockedUntil);
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        AuthService svc = newService(userRepo, mock(TenantProfileRepository.class),
                encoder, mock(JwtUtil.class));
        assertThrows(AuthService.AccountLockedException.class,
                () -> svc.login(loginReq("wrong-again"), null, com.innbucks.userservice.service.AuditContext.none()));
        verify(encoder, never()).matches(any(), any());
        verify(userRepo, never()).save(any());
        assertEquals(lockedUntil, user.getLockedUntil());
    }

    @Test
    void login_expiredLockout_isAutoReset_andWrongPasswordIncrementsFromOne() {
        // Lockout window has elapsed. User retries with the wrong
        // password (typo). The counter must restart at 1 — NOT carry
        // forward the 7 strikes from before the lockout. Otherwise a
        // single mistake after waiting out the lockout would re-lock
        // immediately.
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        java.time.Instant pastLockout = java.time.Instant.now().minus(java.time.Duration.ofMinutes(5));
        User user = aliceWithAttempts(7, pastLockout);
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches(any(), any())).thenReturn(false);

        AuthService svc = newService(userRepo, mock(TenantProfileRepository.class),
                encoder, mock(JwtUtil.class));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> svc.login(loginReq("wrong"), null, com.innbucks.userservice.service.AuditContext.none()));

        assertEquals("Invalid credentials", ex.getMessage());
        assertEquals(1, user.getFailedLoginAttempts(),
                "Expired lockout must reset the counter — a single typo after waiting it out " +
                        "should NOT re-lock immediately");
        assertNull(user.getLockedUntil(),
                "Auto-reset must clear the stale lockedUntil even on a failed attempt");
    }

    @Test
    void login_expiredLockout_andRightPassword_resetsCounterAndIssuesToken() {
        // Happy-path recovery: lockout served, user remembers password.
        // Counter resets, lockedUntil cleared, token issued.
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository customerRepo = mock(CustomerProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        java.time.Instant pastLockout = java.time.Instant.now().minus(java.time.Duration.ofMinutes(5));
        User user = aliceWithAttempts(7, pastLockout);
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        CustomerProfile profile = CustomerProfile.builder().user(user).registrationTier(2).verified(true).build();
        when(customerRepo.findByUserId(101L)).thenReturn(Optional.of(profile));
        when(encoder.matches("right", "hashed")).thenReturn(true);
        when(jwt.generateToken(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(),
                any(), any(), any(), anyLong(), isNull(), any(), any(), anyBoolean())).thenReturn("tok");

        AuthResponseDTO resp = newService(userRepo, mock(TenantProfileRepository.class),
                customerRepo, encoder, jwt).login(loginReq("right"), null, com.innbucks.userservice.service.AuditContext.none());

        assertEquals("tok", resp.getToken());
        assertEquals(0, user.getFailedLoginAttempts(), "successful login must reset the strike counter");
        assertNull(user.getLockedUntil(), "successful login must clear any prior lockout");
    }

    @Test
    void login_wrongPasswordAtThreshold_emitsBothLoginFailureAndAccountLockedAuditRows() {
        // Threshold-crossing wrong-pw must produce TWO audit events:
        // (1) AUTH_LOGIN_FAILURE with reason=wrong_password (same as
        //     every other wrong-pw — keeps dashboards consistent),
        // (2) AUTH_ACCOUNT_LOCKED — a separate marker so SOC dashboards
        //     can alert on the lockout event without grepping every
        //     failure for metadata.attempts==threshold.
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuditService audit = mock(AuditService.class);
        User user = aliceWithAttempts(6, null);
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches(any(), any())).thenReturn(false);

        AuthService svc = withLockoutConfig(new AuthService(userRepo,
                mock(TenantProfileRepository.class),
                mock(CustomerProfileRepository.class), encoder, mock(JwtUtil.class),
                mock(TokenRevocationService.class),
                mock(RefreshTokenService.class),
                mock(RefreshTokenRepository.class),
                audit));

        assertThrows(AuthService.InvalidCredentialsException.class,
                () -> svc.login(loginReq("wrong"), null, AuditContext.none()));

        verify(audit).recordFailure(
                eq(AuditEventType.AUTH_LOGIN_FAILURE),
                isNull(), eq(AuditService.ACTOR_TYPE_ANONYMOUS),
                eq("101"), eq(AuditService.TARGET_TYPE_USER),
                eq("wrong_password"),
                any(), any());
        verify(audit).recordSuccess(
                eq(AuditEventType.AUTH_ACCOUNT_LOCKED),
                isNull(), eq(AuditService.ACTOR_TYPE_SYSTEM),
                eq("101"), eq(AuditService.TARGET_TYPE_USER),
                any(), any());
    }

    @Test
    void login_lockedAccount_emitsRejectedLockedAuditRow_notFailure() {
        // A locked account being probed is structurally different from
        // wrong-pw. Separate audit type so "active brute-force against
        // a known-locked account" can be alerted as a distinct signal.
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuditService audit = mock(AuditService.class);
        java.time.Instant lockedUntil = java.time.Instant.now().plus(java.time.Duration.ofMinutes(20));
        User user = aliceWithAttempts(7, lockedUntil);
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        AuthService svc = withLockoutConfig(new AuthService(userRepo,
                mock(TenantProfileRepository.class),
                mock(CustomerProfileRepository.class), encoder, mock(JwtUtil.class),
                mock(TokenRevocationService.class),
                mock(RefreshTokenService.class),
                mock(RefreshTokenRepository.class),
                audit));

        assertThrows(AuthService.AccountLockedException.class,
                () -> svc.login(loginReq("anything"), null, AuditContext.none()));

        verify(audit).recordFailure(
                eq(AuditEventType.AUTH_LOGIN_REJECTED_LOCKED),
                isNull(), eq(AuditService.ACTOR_TYPE_ANONYMOUS),
                eq("101"), eq(AuditService.TARGET_TYPE_USER),
                eq("account_locked"),
                any(), any());
        // MUST NOT emit AUTH_LOGIN_FAILURE — the row is already locked,
        // so this isn't a "wrong password" event, it's a "probe of a
        // known-locked account".
        verify(audit, never()).recordFailure(
                eq(AuditEventType.AUTH_LOGIN_FAILURE),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void login_unknownIdentifier_emitsFailureAuditRow_withAnonymousActor() {
        // Probes for accounts that don't exist must still be auditable.
        // actor_id is null, actor_type ANONYMOUS, target_id is the
        // offered identifier (so forensics can spot the enumeration
        // pattern).
        UserRepository userRepo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);
        when(userRepo.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        AuthService svc = withLockoutConfig(new AuthService(userRepo,
                mock(TenantProfileRepository.class),
                mock(CustomerProfileRepository.class), mock(PasswordEncoder.class), mock(JwtUtil.class),
                mock(TokenRevocationService.class),
                mock(RefreshTokenService.class),
                mock(RefreshTokenRepository.class),
                audit));
        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("ghost@example.com");
        req.setPassword("anything");

        assertThrows(AuthService.InvalidCredentialsException.class,
                () -> svc.login(req, null, AuditContext.none()));

        verify(audit).recordFailure(
                eq(AuditEventType.AUTH_LOGIN_FAILURE),
                isNull(), eq(AuditService.ACTOR_TYPE_ANONYMOUS),
                eq("ghost@example.com"), eq(AuditService.TARGET_TYPE_USER),
                eq("unknown_identifier"),
                any(), any());
    }

    @Test
    void login_successfulAfterPartialFailures_resetsCounter() {
        // User fat-fingered the password twice (counter=2), then got
        // it right. Counter must reset to 0 — otherwise the next typo
        // weeks later would unfairly push them closer to lockout.
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository customerRepo = mock(CustomerProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        User user = aliceWithAttempts(2, null);
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        CustomerProfile profile = CustomerProfile.builder().user(user).registrationTier(2).verified(true).build();
        when(customerRepo.findByUserId(101L)).thenReturn(Optional.of(profile));
        when(encoder.matches("right", "hashed")).thenReturn(true);
        when(jwt.generateToken(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(),
                any(), any(), any(), anyLong(), isNull(), any(), any(), anyBoolean())).thenReturn("tok");

        newService(userRepo, mock(TenantProfileRepository.class), customerRepo, encoder, jwt)
                .login(loginReq("right"), null, com.innbucks.userservice.service.AuditContext.none());

        assertEquals(0, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil());
    }

    // ----- change-password — typed exception (not the generic catch-all) -----

    /**
     * Builds an AuthService whose JwtUtil + TokenRevocationService accept any
     * token as valid and unrevoked, and resolve the subject to alice@x.co —
     * so a changePassword call exercises the real per-validation paths
     * (password match / same-pw check) instead of being short-circuited on
     * token gates.
     */
    private AuthService changePasswordService(UserRepository userRepo,
                                              PasswordEncoder encoder) {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(anyString())).thenReturn(true);
        when(jwt.extractEmail(anyString())).thenReturn("alice@x.co");
        TokenRevocationService rev = mock(TokenRevocationService.class);
        when(rev.isRevoked(anyString())).thenReturn(false);
        return withLockoutConfig(new AuthService(userRepo, mock(TenantProfileRepository.class),
                mock(CustomerProfileRepository.class), encoder, jwt, rev,
                mock(RefreshTokenService.class),
                mock(RefreshTokenRepository.class),
                mock(AuditService.class)));
    }

    private ChangePasswordRequestDTO changeReq(String current, String next) {
        ChangePasswordRequestDTO r = new ChangePasswordRequestDTO();
        r.setCurrentPassword(current);
        r.setNewPassword(next);
        return r;
    }

    @Test
    void changePassword_wrongCurrentPassword_throwsTypedExceptionWithSpecificMessage() {
        // The bug this contract pins: a wrong-current-password was throwing a
        // bare RuntimeException that the GlobalExceptionHandler catch-all
        // collapsed into "We couldn't process your request" — the user had no
        // idea what to fix. Typed exception now carries the real reason
        // through to the FE.
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        User user = User.builder()
                .id(1L).email("alice@x.co").password("hashed-current")
                .roles(EnumSet.of(User.Role.CUSTOMER)).active(true).build();
        when(userRepo.findByEmail("alice@x.co")).thenReturn(Optional.of(user));
        when(encoder.matches("wrong", "hashed-current")).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        changePasswordService(userRepo, encoder)
                                .changePassword("tok", changeReq("wrong", "new"),
                                        com.innbucks.userservice.service.AuditContext.none()))
                .isInstanceOf(AuthService.PasswordChangeException.class)
                .hasMessage("Current password does not match");
    }

    @Test
    void changePassword_newPasswordSameAsCurrent_throwsTypedExceptionWithSpecificMessage() {
        // Same defence-in-depth requirement: the FE can read the specific
        // "must differ" message and surface it instead of a generic retry hint.
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        User user = User.builder()
                .id(1L).email("alice@x.co").password("hashed-same")
                .roles(EnumSet.of(User.Role.CUSTOMER)).active(true).build();
        when(userRepo.findByEmail("alice@x.co")).thenReturn(Optional.of(user));
        when(encoder.matches("same", "hashed-same")).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        changePasswordService(userRepo, encoder)
                                .changePassword("tok", changeReq("same", "same"),
                                        com.innbucks.userservice.service.AuditContext.none()))
                .isInstanceOf(AuthService.PasswordChangeException.class)
                .hasMessage("New password must differ from current password");
    }
}
