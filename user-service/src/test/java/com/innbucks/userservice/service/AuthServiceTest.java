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

    private RegisterRequestDTO baseRequest(String email, String phone, String... roles) {
        RegisterRequestDTO req = new RegisterRequestDTO();
        req.setFirstName("Jane");
        req.setMiddleName("M");
        req.setLastName("Doe");
        req.setPhoneNumber(phone);
        req.setEmail(email);
        req.setPassword("password123");
        req.setRoles(List.of(roles));
        return req;
    }

    @Test
    void register_systemUser_savesUser_noTenantProfile() {
        UserRepository userRepo = mock(UserRepository.class);
        TenantProfileRepository tenantRepo = mock(TenantProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(userRepo.existsByEmail(any())).thenReturn(false);
        when(userRepo.existsByPhoneNumber(any())).thenReturn(false);
        when(encoder.encode("password123")).thenReturn("hashed");

        RegisterRequestDTO req = baseRequest("sm@example.com", "0777000001", "MERCHANT_ADMIN");

        AuthResponseDTO response = newService(userRepo, tenantRepo, encoder, jwt).register(req);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(saved.capture());
        assertEquals("hashed", saved.getValue().getPassword());
        assertTrue(saved.getValue().getRoles().contains(User.Role.MERCHANT_ADMIN));
        assertFalse(saved.getValue().isMfaEnabled());
        verify(tenantRepo, never()).save(any());
        assertFalse(response.isMfaRequired());
        assertEquals(List.of("MERCHANT_ADMIN"), response.getRoles());
    }

    @Test
    void register_multipleRoles_savesAllAndCreatesTenantProfile() {
        UserRepository userRepo = mock(UserRepository.class);
        TenantProfileRepository tenantRepo = mock(TenantProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(userRepo.existsByEmail(any())).thenReturn(false);
        when(userRepo.existsByPhoneNumber(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hashed");

        RegisterRequestDTO req = baseRequest("multi@example.com", "0777999999",
                "EVENT_ORGANIZER", "MERCHANT_ADMIN");
        req.setDefaultServices(List.of("ticketing", "loyalty"));

        AuthResponseDTO response = newService(userRepo, tenantRepo, encoder, jwt).register(req);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(saved.capture());
        assertTrue(saved.getValue().getRoles().contains(User.Role.EVENT_ORGANIZER));
        assertTrue(saved.getValue().getRoles().contains(User.Role.MERCHANT_ADMIN));
        assertTrue(saved.getValue().getDefaultServices().contains("ticketing"));
        assertTrue(saved.getValue().getDefaultServices().contains("loyalty"));
        // Tenant profile created because EVENT_ORGANIZER is one of the roles
        verify(tenantRepo).save(any());
        assertTrue(response.getRoles().contains("EVENT_ORGANIZER"));
        assertTrue(response.getRoles().contains("MERCHANT_ADMIN"));
    }

    @Test
    void register_tenant_alsoCreatesTenantProfile() {
        UserRepository userRepo = mock(UserRepository.class);
        TenantProfileRepository tenantRepo = mock(TenantProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(userRepo.existsByEmail(any())).thenReturn(false);
        when(userRepo.existsByPhoneNumber(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hashed");

        RegisterRequestDTO req = baseRequest("tenant@example.com", "0777111111", "EVENT_ORGANIZER");

        newService(userRepo, tenantRepo, encoder, jwt).register(req);

        ArgumentCaptor<TenantProfile> savedProfile = ArgumentCaptor.forClass(TenantProfile.class);
        verify(tenantRepo).save(savedProfile.capture());
        assertSame(req.getEmail(), savedProfile.getValue().getUser().getEmail());
    }

    @Test
    void register_rejectsCustomerRoleOnSystemEndpoint() {
        UserRepository userRepo = mock(UserRepository.class);
        AuthService service = newService(userRepo, mock(TenantProfileRepository.class),
                mock(PasswordEncoder.class), mock(JwtUtil.class));

        RegisterRequestDTO req = baseRequest("c@example.com", "0777222222", "CUSTOMER");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.register(req));
        assertTrue(ex.getMessage().toLowerCase().contains("customer"));
        verify(userRepo, never()).save(any());
    }

    @Test
    void register_rejectsDuplicateEmail() {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.existsByEmail("dup@example.com")).thenReturn(true);
        AuthService service = newService(userRepo, mock(TenantProfileRepository.class),
                mock(PasswordEncoder.class), mock(JwtUtil.class));

        RegisterRequestDTO req = baseRequest("dup@example.com", "0777000002", "MERCHANT_ADMIN");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.register(req));
        assertEquals("Email already registered", ex.getMessage());
        verify(userRepo, never()).save(any());
    }

    @Test
    void login_withValidEmail_issuesToken() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        User user = User.builder()
                .id(1L)
                .email("u@example.com").password("hashed")
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN))
                .mfaEnabled(false).build();
        when(userRepo.findByEmail("u@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("pw", "hashed")).thenReturn(true);
        when(jwt.generateToken(eq("u@example.com"), eq(List.of("MERCHANT_ADMIN")),
                any(), eq(4), eq(true), isNull())).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("u@example.com"); req.setPassword("pw");

        AuthResponseDTO resp = newService(userRepo, mock(TenantProfileRepository.class),
                encoder, jwt).login(req);

        assertEquals("tok", resp.getToken());
        assertEquals(List.of("MERCHANT_ADMIN"), resp.getRoles());
        assertFalse(resp.isMfaRequired());
        assertEquals(4, resp.getTier());
        assertEquals(Boolean.TRUE, resp.getVerified());
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
                .mfaEnabled(false).build();
        CustomerProfile profile = CustomerProfile.builder()
                .user(user).registrationTier(2).verified(false).build();
        when(userRepo.findByPhoneNumber("0777000099")).thenReturn(Optional.of(user));
        when(customerRepo.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(encoder.matches("pw", "hashed")).thenReturn(true);
        when(jwt.generateToken(eq("0777000099"), eq(List.of("CUSTOMER")),
                any(), eq(2), eq(false), eq("0777000099"))).thenReturn("tok");

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
                .roles(EnumSet.of(User.Role.CUSTOMER)).mfaEnabled(true).build();
        CustomerProfile profile = CustomerProfile.builder()
                .user(user).registrationTier(1).verified(false).build();
        when(userRepo.findByEmail(any())).thenReturn(Optional.of(user));
        when(customerRepo.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(encoder.matches(any(), any())).thenReturn(true);
        when(jwt.generateToken(eq("u@example.com"), eq(List.of("CUSTOMER")),
                any(), anyInt(), anyBoolean(), isNull())).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setIdentifier("u@example.com"); req.setPassword("pw");

        AuthResponseDTO resp = newService(userRepo, mock(TenantProfileRepository.class),
                customerRepo, encoder, jwt).login(req);

        assertEquals("tok", resp.getToken());
        assertFalse(resp.isMfaRequired());
    }
}
