package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.entity.*;
import com.innbucks.userservice.repository.*;
import com.innbucks.userservice.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final AgentProfileRepository agentProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthResponseDTO register(RegisterRequestDTO request) {
        log.info("Starting registration process for email: {}, role: {}", request.getEmail(), request.getRole());
        if (userRepository.existsByEmail(request.getEmail())) {
            log.warn("Registration failed: Email {} already registered", request.getEmail());
            throw new RuntimeException("Email already registered");
        }

        log.debug("Creating user entity for email: {}", request.getEmail());
        User user = User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phoneNumber(request.getPhoneNumber())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(User.Role.valueOf(request.getRole().toUpperCase()))
                .build();

        userRepository.save(user);
        log.info("User entity saved successfully for email: {}, assigned ID: {}", user.getEmail(), user.getId());

        // If registering as agent, create agent profile
        if (user.getRole() == User.Role.AGENT) {
            log.info("Registering as AGENT, creating agent profile for user ID: {}", user.getId());
            AgentProfile profile = AgentProfile.builder()
                    .user(user)
                    .businessName(request.getBusinessName())
                    .businessAddress(request.getBusinessAddress())
                    .businessEmail(request.getBusinessEmail())
                    .businessPhoneNumber(request.getBusinessPhoneNumber())
                    .registrationNumber(request.getRegistrationNumber())
                    .metaDataFilePath(request.getMetaData())
                    .build();
            agentProfileRepository.save(profile);
            log.info("Agent profile saved successfully for user ID: {}", user.getId());
        }

        log.info("Registration completed successfully for email: {}", user.getEmail());
        return AuthResponseDTO.builder()
                .email(user.getEmail())
                .role(user.getRole().name())
                .mfaRequired(false)
                .build();
    }

    public AuthResponseDTO login(LoginRequestDTO request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid credentials");
        }

        // MFA check
        if (user.isMfaEnabled()) {
            if (request.getOtpCode() == null || request.getOtpCode().isBlank()) {
                return AuthResponseDTO.builder()
                        .mfaRequired(true)
                        .build();
            }
            // TODO: validate OTP code against mfaSecret
        }

        String token = jwtUtil.generateToken(user.getEmail(), user.getRole().name());
        return AuthResponseDTO.builder()
                .token(token)
                .email(user.getEmail())
                .role(user.getRole().name())
                .mfaRequired(false)
                .build();
    }
}
