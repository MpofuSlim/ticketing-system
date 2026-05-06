package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.entity.*;
import com.innbucks.userservice.repository.*;
import com.innbucks.userservice.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final Set<User.Role> SYSTEM_USER_ROLES = EnumSet.of(
            User.Role.SUPER_ADMIN,
            User.Role.EVENT_ORGANIZER,
            User.Role.MERCHANT_ADMIN
    );

    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final DeviceRepository deviceRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenRevocationService tokenRevocationService;

    @Transactional
    public AuthResponseDTO register(RegisterRequestDTO request) {
        log.info("Starting system user registration email={} role={}", request.getEmail(), request.getRole());

        User.Role role = parseRole(request.getRole());
        if (role == User.Role.CUSTOMER) {
            throw new RuntimeException("Customers must register via the /auth/customer/register (tiered) flow");
        }
        if (!SYSTEM_USER_ROLES.contains(role)) {
            throw new RuntimeException("Unsupported role for system registration: " + role);
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            log.warn("Registration failed, email already registered email={}", request.getEmail());
            throw new RuntimeException("Email already registered");
        }
        if (userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
            log.warn("Registration failed, phone already registered phone={}", request.getPhoneNumber());
            throw new RuntimeException("Phone number already registered");
        }

        User user = User.builder()
                .firstName(request.getFirstName())
                .middleName(request.getMiddleName())
                .lastName(request.getLastName())
                .phoneNumber(request.getPhoneNumber())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(role)
                .mfaEnabled(true)
                .mfaSecret(request.getMfa().getSecret())
                .build();

        userRepository.save(user);
        log.info("User entity saved email={} userId={} role={}", user.getEmail(), user.getId(), role);

        Device device = Device.builder()
                .user(user)
                .deviceId(request.getDevice().getDeviceId())
                .deviceName(request.getDevice().getDeviceName())
                .platform(request.getDevice().getPlatform())
                .pushToken(request.getDevice().getPushToken())
                .build();
        deviceRepository.save(device);
        log.info("Device registered userId={} deviceId={}", user.getId(), device.getDeviceId());

        if (role == User.Role.EVENT_ORGANIZER) {
            log.info("Role=EVENT_ORGANIZER, creating tenant profile userId={}", user.getId());
            TenantProfile profile = TenantProfile.builder()
                    .user(user)
                    .build();
            tenantProfileRepository.save(profile);
            log.info("Tenant profile saved userId={}", user.getId());
        }

        log.info("Registration complete email={} role={}", user.getEmail(), role);
        return AuthResponseDTO.builder()
                .email(user.getEmail())
                .role(user.getRole().name())
                .mfaRequired(false)
                .build();
    }

    public AuthResponseDTO login(LoginRequestDTO request) {
        User user = resolveUser(request)
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid credentials");
        }
        if (!user.isActive()) {
            throw new RuntimeException("Account is not active. Please contact a SUPER_ADMIN for approval.");
        }

        String subject = user.getEmail() != null ? user.getEmail() : user.getPhoneNumber();

        int tier;
        boolean verified;
        if (user.getRole() == User.Role.CUSTOMER) {
            CustomerProfile profile = customerProfileRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new RuntimeException("Customer profile missing"));
            tier = profile.getRegistrationTier();
            verified = profile.isVerified();
        } else {
            // System users (SUPER_ADMIN / EVENT_ORGANIZER / MERCHANT_ADMIN) are implicitly fully trusted
            tier = 4;
            verified = true;
        }

        String token = jwtUtil.generateToken(subject, user.getRole().name(), tier, verified,
                user.getPhoneNumber());
        return AuthResponseDTO.builder()
                .token(token)
                .email(user.getEmail())
                .role(user.getRole().name())
                .mfaRequired(false)
                .tier(tier)
                .verified(verified)
                .build();
    }

    public AuthResponseDTO refresh(String token) {
        if (token == null || token.isBlank() || !jwtUtil.isTokenValid(token)) {
            throw new RuntimeException("Invalid or expired token");
        }
        if (tokenRevocationService.isRevoked(token)) {
            throw new RuntimeException("Token revoked");
        }
        String subject = jwtUtil.extractEmail(token);
        if (subject == null || subject.isBlank()) {
            throw new RuntimeException("Token has no subject");
        }
        User user = (subject.contains("@")
                ? userRepository.findByEmail(subject)
                : userRepository.findByPhoneNumber(subject))
                .orElseThrow(() -> new RuntimeException("User not found"));

        int tier;
        boolean verified;
        if (user.getRole() == User.Role.CUSTOMER) {
            CustomerProfile profile = customerProfileRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new RuntimeException("Customer profile missing"));
            tier = profile.getRegistrationTier();
            verified = profile.isVerified();
        } else {
            tier = 4;
            verified = true;
        }

        String newToken = jwtUtil.generateToken(subject, user.getRole().name(), tier, verified,
                user.getPhoneNumber());
        log.info("Token refreshed subject={} role={} tier={} verified={}",
                subject, user.getRole(), tier, verified);
        return AuthResponseDTO.builder()
                .token(newToken)
                .email(user.getEmail())
                .role(user.getRole().name())
                .mfaRequired(false)
                .tier(tier)
                .verified(verified)
                .build();
    }

    private java.util.Optional<User> resolveUser(LoginRequestDTO request) {
        String identifier = request.getIdentifier();
        if (identifier == null || identifier.isBlank()) {
            return java.util.Optional.empty();
        }
        String trimmed = identifier.trim();
        return trimmed.contains("@")
                ? userRepository.findByEmail(trimmed)
                : userRepository.findByPhoneNumber(trimmed);
    }

    private User.Role parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new RuntimeException("Role is required");
        }
        try {
            return User.Role.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new RuntimeException("Unknown role: " + raw);
        }
    }
}
