package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.entity.*;
import com.innbucks.userservice.repository.*;
import com.innbucks.userservice.util.MsisdnMasking;
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
import java.util.UUID;
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

    /** Deployment country pin (ISO 3166-1 alpha-2). System users (EVENT_ORGANIZER,
     *  MERCHANT_ADMIN) created here are by definition rooted in THIS cell, so
     *  their home_country is the deployment country — no MSISDN derivation
     *  for the staff path. Set via INNBUCKS_COUNTRY env var. */
    @Value("${innbucks.country:ZW}")
    private String deploymentCountry;

    // SUPER_ADMIN accounts are seeded without a country, but downstream
    // services (e.g. event-service createEvent) reject a token that carries
    // no country claim. Default superadmins to Zimbabwe. Scoped to
    // SUPER_ADMIN only — for any other role a blank country is a real data
    // gap we don't want to silently paper over.
    private static final String DEFAULT_SUPER_ADMIN_COUNTRY = "Zimbabwe";

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

    /**
     * Thrown by {@link #changePassword(String, ChangePasswordRequestDTO,
     * AuditContext)} when the request can't be processed. The {@link #message}
     * is user-facing and safe to forward verbatim — every constructor argument
     * comes from this class's own throw sites, never from raw upstream text.
     * Mapped to 400 by {@link com.innbucks.userservice.exception.GlobalExceptionHandler}
     * so the FE sees the specific reason ("Current password does not match")
     * instead of the generic catch-all ("We couldn't process your request").
     */
    public static class PasswordChangeException extends RuntimeException {
        public PasswordChangeException(String message) {
            super(message);
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
        // Step 4: use the composite (phone, home_country) check matching the
        // new uk_users_phone_country constraint. System users are anchored
        // to this cell's deployment country.
        if (userRepository.existsByPhoneNumberAndHomeCountry(request.getPhoneNumber(), deploymentCountry)) {
            log.warn("Registration failed, phone already registered phone={}", MsisdnMasking.mask(request.getPhoneNumber()));
            throw new RuntimeException("Phone number already registered");
        }

        // No password is collected at registration. Store an unguessable
        // placeholder so the NOT NULL column is satisfied and the account
        // cannot be logged into; the real default password (#Pass123) is
        // assigned by UserAdminService when a SUPER_ADMIN approves (first
        // activates) the account. Created inactive + unapproved by default.
        User user = User.builder()
                .firstName(request.getFirstName())
                .middleName(request.getMiddleName())
                .lastName(request.getLastName())
                .phoneNumber(request.getPhoneNumber())
                .email(request.getEmail())
                .country(request.getCountry())
                // home_country is the cell-routing key — different from the
                // free-text `country` field above. Staff are rooted in the
                // cell they were created in.
                .homeCountry(deploymentCountry)
                .password(passwordEncoder.encode("!PENDING-" + java.util.UUID.randomUUID()))
                .roles(roles)
                .defaultServices(bundles)
                .business(request.isBusiness())
                .mfaEnabled(false)
                .build();

        userRepository.save(user);
        log.info("User entity saved (pending approval) email={} userId={} roles={} bundles={} isBusiness={}",
                user.getEmail(), user.getId(), roles, bundles, request.isBusiness());

        if (request.isBusiness()) {
            log.info("Business account, creating tenant profile userId={}", user.getId());
            TenantProfile profile = TenantProfile.builder()
                    .user(user)
                    .businessName(request.getBusinessName())
                    .businessAddress(request.getBusinessAddress())
                    .businessEmail(request.getBusinessEmail())
                    .bpoNumber(request.getBpoNumber())
                    .build();
            tenantProfileRepository.save(profile);
            log.info("Tenant profile saved userId={}", user.getId());
        }

        log.info("Registration complete (pending approval) email={} roles={} bundles={}", user.getEmail(), roles, bundles);
        return AuthResponseDTO.builder()
                .email(user.getEmail())
                .roles(roleNames(user.getRoles()))
                .defaultServices(new ArrayList<>(bundles))
                .mfaRequired(false)
                .mustChangePassword(false)
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
     * Rotates the caller's password. Requires the existing (current) password
     * as a re-authentication step. Bumps {@code token_version} as part of the
     * same write so the current JWT (which may carry
     * {@code mustChangePassword: true}) is immediately rejected by every
     * service's JwtFilter — the user re-logs in with the new password to get
     * a fresh JWT without the must-change claim. The cross-service eviction is
     * intentional: it stops a leaked temp password from being used to call any
     * other endpoint after the rightful owner has rotated it.
     */
    @Transactional
    public void changePassword(String token, ChangePasswordRequestDTO request, AuditContext auditContext) {
        if (token == null || token.isBlank() || !jwtUtil.isTokenValid(token)) {
            throw new PasswordChangeException("Invalid or expired token");
        }
        if (tokenRevocationService.isRevoked(token)) {
            throw new PasswordChangeException("Token revoked");
        }
        String subject = jwtUtil.extractEmail(token);
        if (subject == null || subject.isBlank()) {
            throw new PasswordChangeException("Token has no subject");
        }
        User user = (subject.contains("@")
                ? userRepository.findByEmail(subject)
                : userRepository.findByPhoneNumber(subject))
                .orElseThrow(() -> new PasswordChangeException("User not found"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new PasswordChangeException("Current password does not match");
        }
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new PasswordChangeException("New password must differ from current password");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePassword(false);
        // Bump the session epoch — the current JWT may carry
        // mustChangePassword: true; every service's JwtFilter compares the
        // claim's tokenVersion against users.token_version and rejects on
        // mismatch. The next login mints a fresh JWT with the flag off.
        user.setTokenVersion(user.getTokenVersion() + 1);
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

        String country = user.getCountry();
        if ((country == null || country.isBlank()) && user.hasRole(User.Role.SUPER_ADMIN)) {
            country = DEFAULT_SUPER_ADMIN_COUNTRY;
        }

        // Stable cross-service caller identifier + team-scoping claim.
        // organizerUuid resolves to:
        //   - EVENT_ORGANIZER : their own user_uuid (they're the root of their team)
        //   - TEAM_MEMBER     : the parent organizer's user_uuid (stamped at creation)
        //   - everyone else   : null (no team scoping applies)
        // Booking-service uses this to authorize ticket scans without a
        // per-request lookup back into user-service.
        UUID organizerUuid;
        if (user.hasRole(User.Role.EVENT_ORGANIZER)) {
            organizerUuid = user.getUserUuid();
        } else if (user.hasRole(User.Role.TEAM_MEMBER)) {
            organizerUuid = user.getCreatedByOrganizerUuid();
        } else {
            organizerUuid = null;
        }

        String newToken = jwtUtil.generateToken(subject, roleNames, new ArrayList<>(microservices),
                tier, verified, user.getPhoneNumber(), loyaltyMerchantId, loyaltyShopId,
                firstName, middleName, lastName, user.getTokenVersion(), country,
                user.getUserUuid(), organizerUuid, user.isMustChangePassword());

        return AuthResponseDTO.builder()
                .token(newToken)
                .refreshToken(refreshToken)
                .email(user.getEmail())
                .roles(roleNames)
                .defaultServices(bundles)
                .mfaRequired(false)
                .mustChangePassword(user.isMustChangePassword())
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
