package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.entity.*;
import com.innbucks.userservice.repository.*;
import com.innbucks.userservice.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
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
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditService auditService;

    @Value("${innbucks.account-lockout.max-attempts:7}")
    private int maxFailedLoginAttempts;

    @Value("${innbucks.account-lockout.duration-minutes:30}")
    private int lockoutDurationMinutes;

    /**
     * Raised by {@link #login} when the resolved account has an active
     * lockout (see {@link User#getLockedUntil()}). Carries the deadline
     * the FE renders as a countdown; the controller maps this to a
     * 423 LOCKED response and surfaces {@code lockedUntil} in the body.
     */
    public static class AccountLockedException extends RuntimeException {
        private final Instant lockedUntil;

        public AccountLockedException(Instant lockedUntil) {
            super("Account temporarily locked due to too many failed login attempts");
            this.lockedUntil = lockedUntil;
        }

        public Instant getLockedUntil() {
            return lockedUntil;
        }
    }

    /**
     * Raised on every "Invalid credentials" outcome (unknown identifier
     * OR wrong password). A dedicated subclass lets the @Transactional
     * {@code noRollbackFor} preserve the failed_login_attempts /
     * locked_until writes that fire on the wrong-password path —
     * Spring's default rollback-on-RuntimeException would otherwise
     * undo them and the lockout would never actually persist.
     * The 400 response shape matches whatever the generic
     * RuntimeException handler renders ("Invalid credentials" in the
     * message field) so the lockout-transition response stays
     * indistinguishable from wrong-pw-against-nonexistent-account.
     */
    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("Invalid credentials");
        }
    }

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

    // noRollbackFor: the wrong-password branch persists the
    // failed_login_attempts increment (and possibly the locked_until
    // stamp) and THEN throws InvalidCredentialsException so the caller
    // sees a 400. Without this, Spring's default rollback would undo
    // the save and the lockout counter would never accumulate.
    // AccountLockedException doesn't actually need to suppress rollback
    // (it throws without any writes) but is listed for clarity in case
    // we add side-effects later.
    // noRollbackFor: the wrong-password branch persists the
    // failed_login_attempts increment (and possibly the locked_until
    // stamp) and THEN throws InvalidCredentialsException so the caller
    // sees a 400. Without this, Spring's default rollback would undo
    // the save and the lockout counter would never accumulate.
    // AccountLockedException doesn't actually need to suppress rollback
    // (it throws without any writes) but is listed for clarity in case
    // we add side-effects later.
    @Transactional(noRollbackFor = {
            InvalidCredentialsException.class,
            AccountLockedException.class
    })
    public AuthResponseDTO login(LoginRequestDTO request, String deviceId, AuditContext auditContext) {
        java.util.Optional<User> resolved = resolveUser(request);
        if (resolved.isEmpty()) {
            // Unknown identifier. actor stays ANONYMOUS; target_id is
            // the offered identifier so forensics can answer "who's
            // probing for accounts that don't exist".
            auditService.recordFailure(
                    AuditEventType.AUTH_LOGIN_FAILURE,
                    null, AuditService.ACTOR_TYPE_ANONYMOUS,
                    request.getIdentifier(), AuditService.TARGET_TYPE_USER,
                    "unknown_identifier",
                    java.util.Map.of("identifier", String.valueOf(request.getIdentifier())),
                    auditContext);
            throw new InvalidCredentialsException();
        }
        User user = resolved.get();

        Instant now = Instant.now();

        // Active lockout — short-circuit BEFORE the password check so
        // an attacker who happens to guess the right password during a
        // lockout window still can't sneak in. Stale rate-limit /
        // brute-force counters aren't reset here.
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            log.warn("Login rejected — account locked userId={} lockedUntil={}",
                    user.getId(), user.getLockedUntil());
            auditService.recordFailure(
                    AuditEventType.AUTH_LOGIN_REJECTED_LOCKED,
                    null, AuditService.ACTOR_TYPE_ANONYMOUS,
                    String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER,
                    "account_locked",
                    java.util.Map.of("lockedUntil", user.getLockedUntil().toString()),
                    auditContext);
            throw new AccountLockedException(user.getLockedUntil());
        }
        // Lockout served — auto-reset so the user gets a fresh window
        // on this attempt. The reset lands in the DB as part of the
        // success or failure write below, not a separate round-trip.
        if (user.getLockedUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            boolean justLocked = false;
            if (attempts >= maxFailedLoginAttempts) {
                Instant until = now.plus(Duration.ofMinutes(lockoutDurationMinutes));
                user.setLockedUntil(until);
                justLocked = true;
                log.warn("Account locked userId={} attempts={} until={}",
                        user.getId(), attempts, until);
            } else {
                log.info("Failed login attempt userId={} attempts={} threshold={}",
                        user.getId(), attempts, maxFailedLoginAttempts);
            }
            userRepository.save(user);

            auditService.recordFailure(
                    AuditEventType.AUTH_LOGIN_FAILURE,
                    null, AuditService.ACTOR_TYPE_ANONYMOUS,
                    String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER,
                    "wrong_password",
                    java.util.Map.of("attempts", attempts),
                    auditContext);
            if (justLocked) {
                // Separate event so dashboards can alert specifically on
                // "this row just crossed the threshold" without scanning
                // every failure for the metadata.attempts == threshold case.
                auditService.recordSuccess(
                        AuditEventType.AUTH_ACCOUNT_LOCKED,
                        null, AuditService.ACTOR_TYPE_SYSTEM,
                        String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER,
                        java.util.Map.of(
                                "attempts", attempts,
                                "lockedUntil", user.getLockedUntil().toString(),
                                "durationMinutes", lockoutDurationMinutes),
                        auditContext);
            }
            // Same response shape as wrong-pw on a nonexistent account so
            // the lockout-triggering attempt doesn't become an oracle for
            // "this identifier exists". Subsequent attempts will hit the
            // 423 branch above. The @Transactional noRollbackFor on this
            // method keeps the save above committed despite the throw.
            throw new InvalidCredentialsException();
        }
        if (!user.isActive()) {
            auditService.recordFailure(
                    AuditEventType.AUTH_LOGIN_FAILURE,
                    null, AuditService.ACTOR_TYPE_ANONYMOUS,
                    String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER,
                    "account_inactive", null, auditContext);
            throw new RuntimeException("Account is not active. Please contact a SUPER_ADMIN for approval.");
        }

        // Single-active-session: bump the token version BEFORE minting and
        // revoke every still-live refresh-token row for this user. The
        // version bump invalidates every access token previously issued
        // (JwtFilter compares the claim to users.token_version on each
        // request); the refresh-revoke prevents the previous device from
        // extending its session via /auth/refresh. Together they enforce
        // "last login wins" — and the @Transactional means both writes
        // commit atomically. The lockout-counter reset rides on the same
        // save so a successful login wipes any prior strikes.
        long newVersion = user.getTokenVersion() + 1;
        user.setTokenVersion(newVersion);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        int revokedFamilies = refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());
        log.info("Login bumped tokenVersion userId={} newVersion={} revokedRefreshTokens={}",
                user.getId(), newVersion, revokedFamilies);

        auditService.recordSuccess(
                AuditEventType.AUTH_LOGIN_SUCCESS,
                String.valueOf(user.getId()), AuditService.ACTOR_TYPE_USER,
                String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER,
                java.util.Map.of(
                        "tokenVersion", newVersion,
                        "hasDeviceId", deviceId != null && !deviceId.isBlank(),
                        "revokedPriorFamilies", revokedFamilies),
                auditContext);

        return issueToken(user, deviceId);
    }

    /**
     * Rotates the caller's password. Requires the existing (current) password as a
     * re-authentication step. The bearer token stays valid until its natural expiry —
     * other services already accept tokens until then, so forcing immediate revocation
     * here would just create UX friction without a meaningful security gain.
     */
    @Transactional
    public void changePassword(String token, ChangePasswordRequestDTO request, AuditContext auditContext) {
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
        auditService.recordSuccess(
                AuditEventType.AUTH_PASSWORD_CHANGED,
                String.valueOf(user.getId()), AuditService.ACTOR_TYPE_USER,
                String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER,
                null, auditContext);
    }

    /**
     * Rotates the supplied refresh token: consumes it, mints a brand-new
     * refresh token in the same family, and returns a fresh access token
     * with the latest user claims. Replay of an already-rotated refresh
     * token is treated as theft and revokes the entire family.
     *
     * <p>The {@code deviceId} is the raw {@code X-Device-Id} header from
     * the caller. If the original refresh-token row was issued with a
     * device hash, this rotate request MUST present the same device id —
     * mismatch is treated as token theft (family revoked).
     */
    public AuthResponseDTO refresh(String refreshToken, String deviceId, AuditContext auditContext) {
        // Best-effort subject extraction for audit attribution. The
        // RefreshTokenService validates the token's signature again
        // before doing anything; we just want a name to hang the audit
        // row off if something goes wrong. Garbage tokens get a null
        // subject — the row still lands, just with actor_id=null.
        String subject = safeRefreshSubject(refreshToken);
        try {
            RefreshTokenService.Rotation rotation = refreshTokenService.rotate(refreshToken, deviceId);
            AuthResponseDTO response = buildResponse(rotation.user(), rotation.refreshToken());
            log.info("Token refreshed subject={} roles={} tier={} verified={}",
                    rotation.user().getEmail() != null ? rotation.user().getEmail() : rotation.user().getPhoneNumber(),
                    response.getRoles(), response.getTier(), response.getVerified());
            auditService.recordSuccess(
                    AuditEventType.AUTH_REFRESH_SUCCESS,
                    String.valueOf(rotation.user().getId()), AuditService.ACTOR_TYPE_USER,
                    String.valueOf(rotation.user().getId()), AuditService.TARGET_TYPE_USER,
                    java.util.Map.of("hasDeviceId", deviceId != null && !deviceId.isBlank()),
                    auditContext);
            return response;
        } catch (RefreshTokenService.ReuseDetectedException ex) {
            // RefreshTokenService throws the same exception class for
            // both the "token already rotated" replay case AND the
            // "device-id mismatch" case. Both are equally serious
            // (family revoked, customer + attacker forced to relogin)
            // so we audit them under the same broader event type.
            // Operators tail the WARN log line in RefreshTokenService
            // to distinguish "replay" from "device-mismatch" today.
            auditService.recordFailure(
                    AuditEventType.AUTH_REFRESH_REUSE_DETECTED,
                    subject, AuditService.ACTOR_TYPE_USER,
                    subject, AuditService.TARGET_TYPE_USER,
                    "refresh_token_reuse_or_device_mismatch",
                    java.util.Map.of("hasDeviceId", deviceId != null && !deviceId.isBlank()),
                    auditContext);
            throw ex;
        }
    }

    private String safeRefreshSubject(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            return jwtUtil.extractEmail(token);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private AuthResponseDTO issueToken(User user, String deviceId) {
        String refreshToken = refreshTokenService.issueNewFamily(user, deviceId);
        return buildResponse(user, refreshToken);
    }

    private AuthResponseDTO buildResponse(User user, String refreshToken) {
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
                firstName, middleName, lastName, user.getTokenVersion());

        return AuthResponseDTO.builder()
                .token(newToken)
                .refreshToken(refreshToken)
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
