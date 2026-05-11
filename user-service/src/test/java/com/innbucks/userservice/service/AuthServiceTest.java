package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.TenantProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private AuthService newService(UserRepository userRepo,
                                   TenantProfileRepository tenantRepo,
                                   PasswordEncoder encoder,
                                   JwtUtil jwt) {
        return new AuthService(userRepo, tenantRepo,
                mock(CustomerProfileRepository.class), encoder, jwt,
                mock(TokenRevocationService.class));
    }

    private AuthService newService(UserRepository userRepo,
                                   TenantProfileRepository tenantRepo,
                                   CustomerProfileRepository customerRepo,
                                   PasswordEncoder encoder,
                                   JwtUtil jwt) {
        return new AuthService(userRepo, tenantRepo, customerRepo, encoder, jwt,
                mock(TokenRevocationService.class));
    }

    private RegisterRequestDTO baseRequest(String email, String phone, String... bundles) {
        RegisterRequestDTO req = new RegisterRequestDTO();
        req.setFirstName("Jane");
        req.setMiddleName("M");
        req.setLastName("Doe");
        req.setPhoneNumber(phone);
        req.setEmail(email);
        req.setPassword("password123");
        req.setDefaultServices(List.of(bundles));
        return req;
    }

    @Test
    void register_loyaltyBundle_assignsMerchantAdminRole_createsTenantProfile() {
        UserRepository userRepo = mock(UserRepository.class);
        TenantProfileRepository tenantRepo = mock(TenantProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(userRepo.existsByEmail(any())).thenReturn(false);
        when(userRepo.existsByPhoneNumber(any())).thenReturn(false);
        when(encoder.encode("password123")).thenReturn("hashed");

        RegisterRequestDTO req = baseRequest("ma@example.com", "0777000001", "loyalty");

        AuthResponseDTO response = newService(userRepo, tenantRepo, encoder, jwt).register(req);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(saved.capture());
        assertEquals("hashed", saved.getValue().getPassword());
        assertTrue(saved.getValue().getRoles().contains(User.Role.MERCHANT_ADMIN));
        // Bundle list (not the expanded microservices) is what we store and surface
        assertEquals(new LinkedHashSet<>(List.of("loyalty")), saved.getValue().getDefaultServices());
        // MERCHANT_ADMIN now gets a tenant profile (for business info)
        verify(tenantRepo).save(any());
        assertEquals(List.of("MERCHANT_ADMIN"), response.getRoles());
        assertEquals(List.of("loyalty"), response.getDefaultServices());
    }

    @Test
    void register_ticketingBundle_assignsEventOrganizerRole_andTenantProfile() {
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
        // Tenant profile created because the ticketing bundle implies EVENT_ORGANIZER
        verify(tenantRepo).save(any());
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
        verify(tenantRepo).save(any());
        assertTrue(response.getRoles().contains("EVENT_ORGANIZER"));
        assertTrue(response.getRoles().contains("MERCHANT_ADMIN"));
        assertTrue(response.getDefaultServices().contains("ticketing"));
        assertTrue(response.getDefaultServices().contains("loyalty"));
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
        when(jwt.generateToken(eq("u@example.com"), eq(List.of("MERCHANT_ADMIN")),
                eq(List.of("loyalty", "payments")), eq(4), eq(true), isNull(), isNull())).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("u@example.com"); req.setPassword("pw");

        AuthResponseDTO resp = newService(userRepo, mock(TenantProfileRepository.class),
                encoder, jwt).login(req);

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
        when(jwt.generateToken(any(), any(), any(), anyInt(), anyBoolean(), isNull(), isNull())).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("admin@innbucks.co.zw"); req.setPassword("pw");

        AuthResponseDTO resp = newService(userRepo, mock(TenantProfileRepository.class),
                encoder, jwt).login(req);

        assertEquals(List.of("SUPER_ADMIN"), resp.getRoles());
        assertEquals(List.of("ticketing", "loyalty"), resp.getDefaultServices());

        // Verify the JWT was issued with the expanded set covering every microservice.
        ArgumentCaptor<List<String>> servicesCaptor = ArgumentCaptor.forClass(List.class);
        verify(jwt).generateToken(any(), any(), servicesCaptor.capture(), anyInt(), anyBoolean(), isNull(), isNull());
        List<String> services = servicesCaptor.getValue();
        assertTrue(services.contains("events"));
        assertTrue(services.contains("seats"));
        assertTrue(services.contains("bookings"));
        assertTrue(services.contains("payments"));
        assertTrue(services.contains("loyalty"));
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
                any(), eq(2), eq(false), eq("0777000099"), isNull())).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("0777000099"); req.setPassword("pw");

        AuthResponseDTO resp = newService(userRepo, mock(TenantProfileRepository.class),
                customerRepo, encoder, jwt).login(req);

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
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.login(req));
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
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.login(req));
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
        assertThrows(RuntimeException.class, () -> service.login(req));
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
                any(), anyInt(), anyBoolean(), isNull(), isNull())).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("u@example.com"); req.setPassword("pw");

        AuthResponseDTO resp = newService(userRepo, mock(TenantProfileRepository.class),
                customerRepo, encoder, jwt).login(req);

        assertEquals("tok", resp.getToken());
        assertFalse(resp.isMfaRequired());
    }
}
