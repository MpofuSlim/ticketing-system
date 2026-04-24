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
            User.Role.SYSTEM_MANAGER,
            User.Role.TENANT,
            User.Role.MERCHANT_ADMIN,
            User.Role.SHOP_ADMIN,
            User.Role.SHOP_USER,
            User.Role.ADMIN
    );

    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final DeviceRepository deviceRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

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

        if (role == User.Role.TENANT) {
            log.info("Role=TENANT, creating tenant profile userId={}", user.getId());
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

        if (user.isMfaEnabled()) {
            if (request.getOtpCode() == null || request.getOtpCode().isBlank()) {
                return AuthResponseDTO.builder()
                        .mfaRequired(true)
                        .build();
            }
            // TODO: validate OTP code against mfaSecret
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
            // System users (TENANT/ADMIN/etc.) are implicitly fully trusted
            tier = 4;
            verified = true;
        }

        String token = jwtUtil.generateToken(subject, user.getRole().name(), tier, verified);
        return AuthResponseDTO.builder()
                .token(token)
                .email(user.getEmail())
                .role(user.getRole().name())
                .mfaRequired(false)
                .tier(tier)
                .verified(verified)
                .build();
    }

    private java.util.Optional<User> resolveUser(LoginRequestDTO request) {
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            return userRepository.findByEmail(request.getEmail());
        }
        if (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank()) {
            return userRepository.findByPhoneNumber(request.getPhoneNumber());
        }
        return java.util.Optional.empty();
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
