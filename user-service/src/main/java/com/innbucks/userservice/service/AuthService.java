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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenRevocationService tokenRevocationService;

    @Transactional
    public AuthResponseDTO register(RegisterRequestDTO request) {
        log.info("Starting system user registration email={} defaultServices={}",
                request.getEmail(), request.getDefaultServices());

        Set<String> bundles = parseBundles(request.getDefaultServices());
        Set<User.Role> roles = Services.rolesFor(bundles);
        if (roles.isEmpty()) {
            throw new RuntimeException("Could not derive any role from the supplied defaultServices");
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
                .roles(roles)
                .defaultServices(bundles)
                .mfaEnabled(false)
                .build();

        userRepository.save(user);
        log.info("User entity saved email={} userId={} roles={} bundles={}",
                user.getEmail(), user.getId(), roles, bundles);

        if (roles.contains(User.Role.EVENT_ORGANIZER) || roles.contains(User.Role.MERCHANT_ADMIN)) {
            log.info("Roles include business role, creating tenant profile userId={}", user.getId());
            TenantProfile profile = TenantProfile.builder()
                    .user(user)
                    .businessName(request.getBusinessName())
                    .businessAddress(request.getBusinessAddress())
                    .businessPhoneNumber(request.getBusinessContactNumber())
                    .build();
            tenantProfileRepository.save(profile);
            log.info("Tenant profile saved userId={}", user.getId());
        }

        log.info("Registration complete email={} roles={} bundles={}", user.getEmail(), roles, bundles);
        return AuthResponseDTO.builder()
                .email(user.getEmail())
                .roles(roleNames(user.getRoles()))
                .defaultServices(new ArrayList<>(bundles))
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

        return issueToken(user);
    }

    /**
     * Rotates the caller's password. Requires the existing (current) password as a
     * re-authentication step. The bearer token stays valid until its natural expiry —
     * other services already accept tokens until then, so forcing immediate revocation
     * here would just create UX friction without a meaningful security gain.
     */
    @Transactional
    public void changePassword(String token, ChangePasswordRequestDTO request) {
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

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new RuntimeException("Current password does not match");
        }
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new RuntimeException("New password must differ from current password");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("Password changed userId={} subject={}", user.getId(), subject);
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

        AuthResponseDTO response = issueToken(user);
        log.info("Token refreshed subject={} roles={} tier={} verified={}",
                subject, response.getRoles(), response.getTier(), response.getVerified());
        return response;
    }

    private AuthResponseDTO issueToken(User user) {
        String subject = user.getEmail() != null ? user.getEmail() : user.getPhoneNumber();

        int tier;
        boolean verified;
        // Display names ride in the JWT only for tier-2+ CUSTOMERS (tier-1 names
        // are placeholders like "Customer Pending" set by OtpService at signup).
        // Staff roles never carry names — JwtUtil enforces this independently.
        String firstName = null;
        String middleName = null;
        String lastName = null;
        if (user.hasRole(User.Role.CUSTOMER)) {
            CustomerProfile profile = customerProfileRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new RuntimeException("Customer profile missing"));
            tier = profile.getRegistrationTier();
            verified = profile.isVerified();
            if (tier >= 2) {
                firstName = user.getFirstName();
                middleName = user.getMiddleName();
                lastName = user.getLastName();
            }
        } else {
            tier = 4;
            verified = true;
        }

        List<String> roleNames = roleNames(user.getRoles());
        List<String> bundles = user.getDefaultServices() == null
                ? List.of() : new ArrayList<>(user.getDefaultServices());

        // SUPER_ADMIN is granted access to every microservice across every bundle, even if their
        // stored bundle list happens to be empty. Otherwise, expand the picked bundles to their
        // backing microservices for the JWT services claim.
        Set<String> microservices = user.hasRole(User.Role.SUPER_ADMIN)
                ? Services.expandToMicroservices(Services.ALL_BUNDLES)
                : Services.expandToMicroservices(bundles);

        // Shop staff carry both shopId and merchantId stamped on their User row by
        // ShopStaffService at creation time — no lookup required. MERCHANT_ADMIN tokens
        // intentionally do NOT carry a merchantId claim; endpoints that need a merchant
        // scope read it from the request body (e.g. ShopRequest.merchantId).
        java.util.UUID loyaltyMerchantId = null;
        java.util.UUID loyaltyShopId = null;
        if (user.hasRole(User.Role.SHOP_ADMIN) || user.hasRole(User.Role.SHOP_USER)) {
            loyaltyShopId = user.getLoyaltyShopId();
            loyaltyMerchantId = user.getLoyaltyMerchantId();
        }

        String newToken = jwtUtil.generateToken(subject, roleNames, new ArrayList<>(microservices),
                tier, verified, user.getPhoneNumber(), loyaltyMerchantId, loyaltyShopId,
                firstName, middleName, lastName);

        return AuthResponseDTO.builder()
                .token(newToken)
                .email(user.getEmail())
                .roles(roleNames)
                .defaultServices(bundles)
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

    private Set<String> parseBundles(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new RuntimeException("At least one default service is required");
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String b : raw) {
            if (b == null || b.isBlank()) {
                throw new RuntimeException("defaultServices values must be non-blank");
            }
            String normalised = b.trim().toLowerCase(Locale.ROOT);
            if (!Services.isKnownBundle(normalised)) {
                throw new RuntimeException("Unknown service bundle: " + b
                        + ". Allowed values: " + Services.ALL_BUNDLES);
            }
            parsed.add(normalised);
        }
        return parsed;
    }

    private List<String> roleNames(Set<User.Role> roles) {
        if (roles == null) return List.of();
        return roles.stream().map(Enum::name).collect(Collectors.toList());
    }
}
