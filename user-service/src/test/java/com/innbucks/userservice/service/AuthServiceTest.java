package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.Device;
import com.innbucks.userservice.entity.TenantProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.DeviceRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private AuthService newService(UserRepository userRepo,
                                   TenantProfileRepository tenantRepo,
                                   DeviceRepository deviceRepo,
                                   PasswordEncoder encoder,
                                   JwtUtil jwt) {
        return new AuthService(userRepo, tenantRepo,
                mock(CustomerProfileRepository.class), deviceRepo, encoder, jwt);
    }

    private AuthService newService(UserRepository userRepo,
                                   TenantProfileRepository tenantRepo,
                                   CustomerProfileRepository customerRepo,
                                   DeviceRepository deviceRepo,
                                   PasswordEncoder encoder,
                                   JwtUtil jwt) {
        return new AuthService(userRepo, tenantRepo, customerRepo, deviceRepo, encoder, jwt);
    }

    private RegisterRequestDTO baseRequest(String email, String phone, String role) {
        RegisterRequestDTO req = new RegisterRequestDTO();
        req.setFirstName("Jane");
        req.setMiddleName("M");
        req.setLastName("Doe");
        req.setPhoneNumber(phone);
        req.setEmail(email);
        req.setPassword("password123");
        req.setRole(role);

        DeviceRegistrationDTO device = new DeviceRegistrationDTO();
        device.setDeviceId("device-123");
        device.setDeviceName("iPhone 15");
        device.setPlatform("iOS");
        device.setPushToken("token");
        req.setDevice(device);

        MfaRegistrationDTO mfa = new MfaRegistrationDTO();
        mfa.setMethod("TOTP");
        mfa.setSecret("SECRET");
        req.setMfa(mfa);

        return req;
    }

    @Test
    void register_systemUser_savesUserAndDevice_noTenantProfile() {
        UserRepository userRepo = mock(UserRepository.class);
        TenantProfileRepository tenantRepo = mock(TenantProfileRepository.class);
        DeviceRepository deviceRepo = mock(DeviceRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(userRepo.existsByEmail(any())).thenReturn(false);
        when(userRepo.existsByPhoneNumber(any())).thenReturn(false);
        when(encoder.encode("password123")).thenReturn("hashed");

        RegisterRequestDTO req = baseRequest("sm@example.com", "0777000001", "SYSTEM_MANAGER");

        AuthResponseDTO response = newService(userRepo, tenantRepo, deviceRepo, encoder, jwt).register(req);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(saved.capture());
        assertEquals("hashed", saved.getValue().getPassword());
        assertEquals(User.Role.SYSTEM_MANAGER, saved.getValue().getRole());
        assertTrue(saved.getValue().isMfaEnabled());
        assertEquals("SECRET", saved.getValue().getMfaSecret());
        verify(tenantRepo, never()).save(any());
        verify(deviceRepo).save(any(Device.class));
        assertFalse(response.isMfaRequired());
        assertEquals("SYSTEM_MANAGER", response.getRole());
    }

    @Test
    void register_tenant_alsoCreatesTenantProfile() {
        UserRepository userRepo = mock(UserRepository.class);
        TenantProfileRepository tenantRepo = mock(TenantProfileRepository.class);
        DeviceRepository deviceRepo = mock(DeviceRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(userRepo.existsByEmail(any())).thenReturn(false);
        when(userRepo.existsByPhoneNumber(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hashed");

        RegisterRequestDTO req = baseRequest("tenant@example.com", "0777111111", "TENANT");

        newService(userRepo, tenantRepo, deviceRepo, encoder, jwt).register(req);

        ArgumentCaptor<TenantProfile> savedProfile = ArgumentCaptor.forClass(TenantProfile.class);
        verify(tenantRepo).save(savedProfile.capture());
        assertSame(req.getEmail(), savedProfile.getValue().getUser().getEmail());
    }

    @Test
    void register_rejectsCustomerRoleOnSystemEndpoint() {
        UserRepository userRepo = mock(UserRepository.class);
        AuthService service = newService(userRepo, mock(TenantProfileRepository.class),
                mock(DeviceRepository.class), mock(PasswordEncoder.class), mock(JwtUtil.class));

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
                mock(DeviceRepository.class), mock(PasswordEncoder.class), mock(JwtUtil.class));

        RegisterRequestDTO req = baseRequest("dup@example.com", "0777000002", "SHOP_USER");

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
                .email("u@example.com").password("hashed").role(User.Role.SHOP_USER)
                .mfaEnabled(false).build();
        when(userRepo.findByEmail("u@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("pw", "hashed")).thenReturn(true);
        when(jwt.generateToken("u@example.com", "SHOP_USER", 4, true)).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setEmail("u@example.com"); req.setPassword("pw");

        AuthResponseDTO resp = newService(userRepo, mock(TenantProfileRepository.class),
                mock(DeviceRepository.class), encoder, jwt).login(req);

        assertEquals("tok", resp.getToken());
        assertEquals("SHOP_USER", resp.getRole());
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
                .phoneNumber("0777000099").password("hashed").role(User.Role.CUSTOMER)
                .mfaEnabled(false).build();
        CustomerProfile profile = CustomerProfile.builder()
                .user(user).registrationTier(2).verified(false).build();
        when(userRepo.findByPhoneNumber("0777000099")).thenReturn(Optional.of(user));
        when(customerRepo.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(encoder.matches("pw", "hashed")).thenReturn(true);
        when(jwt.generateToken("0777000099", "CUSTOMER", 2, false)).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setPhoneNumber("0777000099"); req.setPassword("pw");

        AuthResponseDTO resp = newService(userRepo, mock(TenantProfileRepository.class),
                customerRepo, mock(DeviceRepository.class), encoder, jwt).login(req);

        assertEquals("tok", resp.getToken());
        assertEquals("CUSTOMER", resp.getRole());
        assertEquals(2, resp.getTier());
        assertEquals(Boolean.FALSE, resp.getVerified());
    }

    @Test
    void login_withWrongPassword_throws() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        User user = User.builder().email("u@example.com").password("hashed")
                .role(User.Role.CUSTOMER).build();
        when(userRepo.findByEmail("u@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches(any(), any())).thenReturn(false);

        LoginRequestDTO req = new LoginRequestDTO();
        req.setEmail("u@example.com"); req.setPassword("wrong");

        AuthService service = newService(userRepo, mock(TenantProfileRepository.class),
                mock(DeviceRepository.class), encoder, mock(JwtUtil.class));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.login(req));
        assertEquals("Invalid credentials", ex.getMessage());
    }

    @Test
    void login_withUnknownEmail_throws() {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findByEmail(any())).thenReturn(Optional.empty());

        LoginRequestDTO req = new LoginRequestDTO();
        req.setEmail("missing@example.com"); req.setPassword("pw");

        AuthService service = newService(userRepo, mock(TenantProfileRepository.class),
                mock(DeviceRepository.class), mock(PasswordEncoder.class), mock(JwtUtil.class));
        assertThrows(RuntimeException.class, () -> service.login(req));
    }

    @Test
    void login_withMfaEnabledAndNoOtp_returnsMfaRequired() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        User user = User.builder().email("u@example.com").password("hashed")
                .role(User.Role.CUSTOMER).mfaEnabled(true).build();
        when(userRepo.findByEmail(any())).thenReturn(Optional.of(user));
        when(encoder.matches(any(), any())).thenReturn(true);

        LoginRequestDTO req = new LoginRequestDTO();
        req.setEmail("u@example.com"); req.setPassword("pw"); // no OTP

        AuthResponseDTO resp = newService(userRepo, mock(TenantProfileRepository.class),
                mock(DeviceRepository.class), encoder, jwt).login(req);

        assertTrue(resp.isMfaRequired());
        assertNull(resp.getToken());
        verify(jwt, never()).generateToken(any(), any(), anyInt(), anyBoolean());
    }
}
