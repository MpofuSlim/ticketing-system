package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.AuthResponseDTO;
import com.innbucks.userservice.dto.LoginRequestDTO;
import com.innbucks.userservice.dto.RegisterRequestDTO;
import com.innbucks.userservice.entity.AgentProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.AgentProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private AuthService newService(UserRepository userRepo,
                                   AgentProfileRepository agentRepo,
                                   PasswordEncoder encoder,
                                   JwtUtil jwt) {
        return new AuthService(userRepo, agentRepo, encoder, jwt);
    }

    @Test
    void register_customer_savesUser_andDoesNotCreateAgentProfile() {
        UserRepository userRepo = mock(UserRepository.class);
        AgentProfileRepository agentRepo = mock(AgentProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(userRepo.existsByEmail("c@example.com")).thenReturn(false);
        when(encoder.encode("pw")).thenReturn("hashed");

        RegisterRequestDTO req = new RegisterRequestDTO();
        req.setFirstName("C"); req.setLastName("U"); req.setPhoneNumber("0");
        req.setEmail("c@example.com"); req.setPassword("pw"); req.setRole("customer");

        AuthResponseDTO response = newService(userRepo, agentRepo, encoder, jwt).register(req);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(saved.capture());
        assertEquals("hashed", saved.getValue().getPassword());
        assertEquals(User.Role.CUSTOMER, saved.getValue().getRole());
        verify(agentRepo, never()).save(any());
        assertFalse(response.isMfaRequired());
        assertEquals("CUSTOMER", response.getRole());
    }

    @Test
    void register_agent_alsoCreatesAgentProfile() {
        UserRepository userRepo = mock(UserRepository.class);
        AgentProfileRepository agentRepo = mock(AgentProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(userRepo.existsByEmail(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hashed");

        RegisterRequestDTO req = new RegisterRequestDTO();
        req.setFirstName("A"); req.setLastName("G"); req.setPhoneNumber("0");
        req.setEmail("a@example.com"); req.setPassword("pw"); req.setRole("AGENT");
        req.setBusinessName("Acme"); req.setBusinessAddress("1 St");
        req.setBusinessEmail("b@acme"); req.setBusinessPhoneNumber("1");
        req.setRegistrationNumber("R1"); req.setMetaData("meta");

        newService(userRepo, agentRepo, encoder, jwt).register(req);

        ArgumentCaptor<AgentProfile> savedProfile = ArgumentCaptor.forClass(AgentProfile.class);
        verify(agentRepo).save(savedProfile.capture());
        assertEquals("Acme", savedProfile.getValue().getBusinessName());
        assertEquals("R1", savedProfile.getValue().getRegistrationNumber());
    }

    @Test
    void register_rejectsDuplicateEmail() {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.existsByEmail("dup@example.com")).thenReturn(true);
        AuthService service = newService(userRepo, mock(AgentProfileRepository.class),
                mock(PasswordEncoder.class), mock(JwtUtil.class));

        RegisterRequestDTO req = new RegisterRequestDTO();
        req.setEmail("dup@example.com"); req.setPassword("pw"); req.setRole("customer");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.register(req));
        assertEquals("Email already registered", ex.getMessage());
        verify(userRepo, never()).save(any());
    }

    @Test
    void login_withValidCredentials_issuesToken() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtUtil jwt = mock(JwtUtil.class);
        User user = User.builder()
                .email("u@example.com").password("hashed").role(User.Role.CUSTOMER)
                .mfaEnabled(false).build();
        when(userRepo.findByEmail("u@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("pw", "hashed")).thenReturn(true);
        when(jwt.generateToken("u@example.com", "CUSTOMER")).thenReturn("tok");

        LoginRequestDTO req = new LoginRequestDTO();
        req.setEmail("u@example.com"); req.setPassword("pw");

        AuthResponseDTO resp = newService(userRepo, mock(AgentProfileRepository.class), encoder, jwt).login(req);

        assertEquals("tok", resp.getToken());
        assertEquals("CUSTOMER", resp.getRole());
        assertFalse(resp.isMfaRequired());
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

        AuthService service = newService(userRepo, mock(AgentProfileRepository.class), encoder, mock(JwtUtil.class));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.login(req));
        assertEquals("Invalid credentials", ex.getMessage());
    }

    @Test
    void login_withUnknownEmail_throws() {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findByEmail(any())).thenReturn(Optional.empty());

        LoginRequestDTO req = new LoginRequestDTO();
        req.setEmail("missing@example.com"); req.setPassword("pw");

        AuthService service = newService(userRepo, mock(AgentProfileRepository.class),
                mock(PasswordEncoder.class), mock(JwtUtil.class));
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

        AuthResponseDTO resp = newService(userRepo, mock(AgentProfileRepository.class), encoder, jwt).login(req);

        assertTrue(resp.isMfaRequired());
        assertNull(resp.getToken());
        verify(jwt, never()).generateToken(any(), any());
    }
}
