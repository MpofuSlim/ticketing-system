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

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenRevocationService tokenRevocationService;

    @Transactional
    public AuthResponseDTO register(RegisterRequestDTO request) {
        log.info("Starting system user registration email={} roles={}", request.getEmail(), request.getRoles());

        Set<User.Role> roles = parseRoles(request.getRoles());
        if (roles.contains(User.Role.CUSTOMER)) {
            throw new RuntimeException("Customers must register via the /auth/customer/register (tiered) flow");
        }
        for (User.Role r : roles) {
            if (!SYSTEM_USER_ROLES.contains(r)) {
                throw new RuntimeException("Unsupported role for system registration: " + r);
            }
        }

        Set<String> defaultServices = normalizeServices(request.getDefaultServices());

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
                .roles(roles)
                .defaultServices(defaultServices)
                .mfaEnabled(false)
                .build();

        userRepository.save(user);
        log.info("User entity saved email={} userId={} roles={} services={}",
                user.getEmail(), user.getId(), roles, defaultServices);

        if (roles.contains(User.Role.EVENT_ORGANIZER)) {
            log.info("Roles include EVENT_ORGANIZER, creating tenant profile userId={}", user.getId());
            TenantProfile profile = TenantProfile.builder()
                    .user(user)
                    .build();
            tenantProfileRepository.save(profile);
            log.info("Tenant profile saved userId={}", user.getId());
        }

        log.info("Registration complete email={} roles={}", user.getEmail(), roles);
        return AuthResponseDTO.builder()
                .email(user.getEmail())
                .roles(roleNames(user.getRoles()))
                .defaultServices(new ArrayList<>(user.getDefaultServices()))
                .mfaRequired(false)
                .build();
    }

    public AuthResponseDTO login(LoginRequestDTO request) {
        User user = resolveUser(request)
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid credentials");
        }

        String subject = user.getEmail() != null ? user.getEmail() : user.getPhoneNumber();

        int tier;
        boolean verified;
        if (user.hasRole(User.Role.CUSTOMER)) {
            CustomerProfile profile = customerProfileRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new RuntimeException("Customer profile missing"));
            tier = profile.getRegistrationTier();
            verified = profile.isVerified();
        } else {
            // System users (SUPER_ADMIN / EVENT_ORGANIZER / MERCHANT_ADMIN) are implicitly fully trusted
            tier = 4;
            verified = true;
        }

        List<String> roleNames = roleNames(user.getRoles());
        List<String> services = new ArrayList<>(
                user.getDefaultServices() == null ? Collections.emptySet() : user.getDefaultServices());
        String token = jwtUtil.generateToken(subject, roleNames, services, tier, verified, user.getPhoneNumber());
        return AuthResponseDTO.builder()
                .token(token)
                .email(user.getEmail())
                .roles(roleNames)
                .defaultServices(services)
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
        if (user.hasRole(User.Role.CUSTOMER)) {
            CustomerProfile profile = customerProfileRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new RuntimeException("Customer profile missing"));
            tier = profile.getRegistrationTier();
            verified = profile.isVerified();
        } else {
            tier = 4;
            verified = true;
        }

        List<String> roleNames = roleNames(user.getRoles());
        List<String> services = new ArrayList<>(
                user.getDefaultServices() == null ? Collections.emptySet() : user.getDefaultServices());
        String newToken = jwtUtil.generateToken(subject, roleNames, services, tier, verified, user.getPhoneNumber());
        log.info("Token refreshed subject={} roles={} tier={} verified={}",
                subject, roleNames, tier, verified);
        return AuthResponseDTO.builder()
                .token(newToken)
                .email(user.getEmail())
                .roles(roleNames)
                .defaultServices(services)
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

    private Set<User.Role> parseRoles(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new RuntimeException("At least one role is required");
        }
        Set<User.Role> parsed = EnumSet.noneOf(User.Role.class);
        for (String r : raw) {
            if (r == null || r.isBlank()) {
                throw new RuntimeException("Role values must be non-blank");
            }
            try {
                parsed.add(User.Role.valueOf(r.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                throw new RuntimeException("Unknown role: " + r);
            }
        }
        return parsed;
    }

    private Set<String> normalizeServices(List<String> raw) {
        if (raw == null) return new HashSet<>();
        return raw.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> roleNames(Set<User.Role> roles) {
        if (roles == null) return List.of();
        return roles.stream().map(Enum::name).collect(Collectors.toList());
    }
}
