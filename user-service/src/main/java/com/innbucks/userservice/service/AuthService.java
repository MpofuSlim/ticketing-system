package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.entity.*;
import com.innbucks.userservice.event.AccountLockedEvent;
import com.innbucks.userservice.repository.*;
import com.innbucks.userservice.util.MsisdnMasking;
import com.innbucks.userservice.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
public class AuthService implements ApplicationEventPublisherAware {

    // Setter-injected (not constructor) so @RequiredArgsConstructor — and every
    // unit-test construction of AuthService — is unaffected. Null only in a
    // plain unit test that didn't set it; publishAccountLocked() null-guards.
    private ApplicationEventPublisher eventPublisher;

    // MFA collaborators — setter-injected so the existing 8 AuthServiceTest
    // construction sites don't need to widen. A login that finds them null
    // (only in plain unit tests) behaves as if MFA were disabled, matching the
    // pre-feature behaviour those tests were written against.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.innbucks.userservice.security.MfaPolicy mfaPolicy;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.innbucks.userservice.security.MfaTokenService mfaTokenService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MfaService mfaService;
    // "Remember this device" trusted-device bypass. Setter-/field-injected like
    // the other MFA collaborators so the existing AuthServiceTest construction
    // sites don't widen. Null in a plain unit test means no trusted-device skip
    // (and no trust minted) — i.e. every login still goes through the MFA gate,
    // matching the pre-feature behaviour those tests assert.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DeviceTrustService deviceTrustService;

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.eventPublisher = applicationEventPublisher;
    }

    private void publishAccountLocked(User user, Instant lockedUntil) {
        if (eventPublisher == null) {
            return; // plain unit test without a Spring context
        }
        eventPublisher.publishEvent(new AccountLockedEvent(
                user.getId(), user.getFirstName(), user.getEmail(), user.getPhoneNumber(),
                user.hasRole(User.Role.CUSTOMER), lockedUntil));
    }

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
     * Raised by {@link #login} when the resolved account has been registered
     * but never approved (first-activated) by a SUPER_ADMIN. Such an account
     * carries only an unusable placeholder password, so a login attempt would
     * otherwise fail the password check and surface the generic
     * "Invalid credentials" — misleading for someone who has simply not been
     * approved yet. The exception's message is a typed constant, safe to
     * passthrough. The handler maps it to 403 with a distinct
     * {@code account_pending_approval} error code so the FE can route the user
     * to a "pending approval" screen rather than a "wrong password" one.
     */
    public static class AccountPendingApprovalException extends RuntimeException {
        public AccountPendingApprovalException() {
            super("Your account is pending approval. You'll be able to sign in once an administrator approves it.");
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "We don't have a role available for the services you selected.");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            log.warn("Registration failed, email already registered email={}", request.getEmail());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email already registered");
        }
        // Step 4: use the composite (phone, home_country) check matching the
        // new uk_users_phone_country constraint. System users are anchored
        // to this cell's deployment country.
        if (userRepository.existsByPhoneNumberAndHomeCountry(request.getPhoneNumber(), deploymentCountry)) {
            log.warn("Registration failed, phone already registered phone={}", MsisdnMasking.mask(request.getPhoneNumber()));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone number already registered");
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
    @Transactional(noRollbackFor = {
            InvalidCredentialsException.class,
            AccountLockedException.class
    })
    /**
     * Backwards-compatible overload used by tests / callers that don't have a
     * channel header. Defaults to WEB — the strictest-by-default policy means
     * unit tests get the same MFA decisions a real WEB login would.
     */
    public AuthResponseDTO login(LoginRequestDTO request, String deviceId, AuditContext auditContext) {
        return login(request, deviceId,
                com.innbucks.userservice.security.AuthChannel.WEB, auditContext);
    }

    /**
     * Backwards-compatible overload that carries no trusted-device token —
     * delegates to the full 5-arg method with a null trust token (so the
     * trusted-device skip never fires). Preserves the existing 4-arg signature
     * that tests call.
     */
    public AuthResponseDTO login(LoginRequestDTO request, String deviceId,
                                 com.innbucks.userservice.security.AuthChannel channel,
                                 AuditContext auditContext) {
        return login(request, deviceId, null, channel, auditContext);
    }

    // This 5-arg method is the real transactional boundary. The 3-/4-arg
    // overloads above delegate here by a self-invocation (which bypasses the
    // Spring proxy), so the annotation MUST live here too — otherwise a direct
    // caller (the controller, which always passes the channel) runs the
    // failed-attempt / last-login writes with no active transaction and Hibernate
    // throws InvalidDataAccessApiUsageException ("No active transaction for update
    // or delete query"). noRollbackFor mirrors the overload so a bad-credential /
    // lockout failure still commits its counter increment + audit row.
    //
    // deviceTrustToken is the raw X-Device-Trust-Token header (nullable). When a
    // login that WOULD be MFA-challenged presents a matching, non-expired trust
    // token for this (user, deviceId), the whole challenge is skipped — see the
    // trusted-device check inside the MFA gate below.
    @Transactional(noRollbackFor = {
            InvalidCredentialsException.class,
            AccountLockedException.class
    })
    public AuthResponseDTO login(LoginRequestDTO request, String deviceId, String deviceTrustToken,
                                 com.innbucks.userservice.security.AuthChannel channel,
                                 AuditContext auditContext) {
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

        // Registered-but-not-yet-approved accounts (created via /auth/register)
        // land inactive + unapproved with an unusable placeholder password, so a
        // login attempt would otherwise fail the password check below and return
        // the generic "Invalid credentials" — misleading for someone who is simply
        // waiting on a SUPER_ADMIN to approve them. Surface the real reason up
        // front instead. This MUST run before the password check because a pending
        // account has no real password to match. Every other account type is
        // created already-approved (OtpService / TeamMemberService /
        // ShopStaffService) and pre-existing rows are back-filled by V26, so
        // "inactive AND unapproved" is uniquely a still-pending registration — a
        // deactivated (previously-approved) account keeps approved=true and falls
        // through to the not-active check further down.
        if (!user.isActive() && !user.isApproved()) {
            log.info("Login rejected — account pending approval userId={}", user.getId());
            auditService.recordFailure(
                    AuditEventType.AUTH_LOGIN_FAILURE,
                    null, AuditService.ACTOR_TYPE_ANONYMOUS,
                    String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER,
                    "account_pending_approval", null, auditContext);
            throw new AccountPendingApprovalException();
        }

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
            Instant lockedUntil = null;
            if (attempts >= maxFailedLoginAttempts) {
                lockedUntil = now.plus(Duration.ofMinutes(lockoutDurationMinutes));
                user.setLockedUntil(lockedUntil);
                justLocked = true;
                log.warn("Account locked userId={} attempts={} until={}",
                        user.getId(), attempts, lockedUntil);
            } else {
                log.info("Failed login attempt userId={} attempts={} threshold={}",
                        user.getId(), attempts, maxFailedLoginAttempts);
            }
            userRepository.save(user);
            if (justLocked) {
                publishAccountLocked(user, lockedUntil);
            }

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

        // MFA gate. On USSD/WhatsApp the policy bypasses 2FA entirely; on
        // WEB/MOBILE, system users MUST satisfy 2FA, customers only if they've
        // opted in. mfaPolicy is null only in plain unit tests (no Spring) —
        // such tests get the pre-feature behaviour (no MFA challenge).
        if (mfaPolicy != null && mfaPolicy.shouldChallenge(user, channel)) {
            // Trusted-device bypass — BEFORE any mfaToken is minted. If this
            // device was "remembered" at a prior step-2 (matching, non-expired
            // trust token presented for THIS user+device), skip the whole 2FA
            // challenge and issue the real token. A mismatched / expired /
            // unknown token is NOT an error — isTrusted() returns false and we
            // fall through to the normal challenge below.
            if (deviceTrustService != null
                    && deviceId != null && !deviceId.isBlank()
                    && deviceTrustToken != null && !deviceTrustToken.isBlank()
                    && deviceTrustService.isTrusted(user.getId(), deviceId, deviceTrustToken)) {
                log.info("MFA skipped via trusted device userId={}", user.getId());
                auditService.recordSuccess(
                        AuditEventType.AUTH_LOGIN_SUCCESS,
                        String.valueOf(user.getId()), AuditService.ACTOR_TYPE_USER,
                        String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER,
                        java.util.Map.of("mfaSkippedViaTrustedDevice", true),
                        auditContext);
                return issueToken(user, deviceId);
            }
            if (!user.isMfaEnabled() || user.getMfaSecret() == null) {
                // System user on web/mobile but no secret yet → force enrolment.
                String enrolToken = mfaTokenService.issue(user.getId(),
                        com.innbucks.userservice.security.MfaTokenService.Purpose.ENROLLMENT);
                log.info("Login requires MFA enrolment userId={}", user.getId());
                return AuthResponseDTO.builder()
                        .mfaEnrollmentRequired(true)
                        .mfaToken(enrolToken)
                        .build();
            }
            String loginToken = mfaTokenService.issue(user.getId(),
                    com.innbucks.userservice.security.MfaTokenService.Purpose.LOGIN_MFA);
            log.info("Login requires MFA code userId={}", user.getId());
            return AuthResponseDTO.builder()
                    .mfaRequired(true)
                    .mfaToken(loginToken)
                    .build();
        }

        return issueToken(user, deviceId);
    }

    /**
     * Backwards-compatible overload — completes a step-2 MFA login WITHOUT
     * trusting the device. Preserves the 4-arg signature existing callers use.
     */
    public AuthResponseDTO completeLoginWithMfa(String mfaToken, String code, String deviceId,
                                                AuditContext auditContext) {
        return completeLoginWithMfa(mfaToken, code, deviceId, false, auditContext);
    }

    /**
     * Step 2 of an MFA-required login: verify the TOTP / backup code carried
     * with the step-1 mfaToken, and (if it matches) mint the real access +
     * refresh tokens via {@link #issueToken}. Same authorization model as the
     * original {@code login} from here on.
     *
     * <p>When {@code rememberDevice} is true AND a non-blank {@code deviceId}
     * was supplied, this also mints a one-time "remember this device" trust
     * token (via {@link DeviceTrustService}), persists its hash against the
     * device, and returns the RAW token + expiry on the response so the client
     * can present it on future logins to skip the 2FA challenge.
     */
    @Transactional(noRollbackFor = {InvalidCredentialsException.class,
            MfaService.MfaException.class,
            com.innbucks.userservice.security.MfaTokenService.InvalidMfaTokenException.class})
    public AuthResponseDTO completeLoginWithMfa(String mfaToken, String code, String deviceId,
                                                boolean rememberDevice, AuditContext auditContext) {
        if (mfaPolicy == null || mfaTokenService == null || mfaService == null) {
            // Misconfigured / unit-test path — fail closed.
            throw new MfaService.MfaException("MFA is not available");
        }
        Long userId = mfaTokenService.verify(mfaToken,
                com.innbucks.userservice.security.MfaTokenService.Purpose.LOGIN_MFA);
        User user = userRepository.findById(userId)
                .orElseThrow(InvalidCredentialsException::new);
        if (!mfaService.verifyForLogin(user, code)) {
            auditService.recordFailure(
                    AuditEventType.AUTH_LOGIN_FAILURE,
                    String.valueOf(user.getId()), AuditService.ACTOR_TYPE_USER,
                    String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER,
                    "mfa_code_invalid", java.util.Map.of(), auditContext);
            throw new MfaService.MfaException("That code didn't match. Try the next one your app shows.");
        }

        // Mint device trust ONLY when the user opted in AND we can scope it to a
        // device. The raw token is surfaced once on the response below; only its
        // hash is persisted. deviceTrustService is null in plain unit tests — no
        // trust is minted there (matches pre-feature behaviour).
        DeviceTrustService.TrustGrant trustGrant = null;
        boolean deviceTrusted = false;
        if (rememberDevice && deviceId != null && !deviceId.isBlank() && deviceTrustService != null) {
            trustGrant = deviceTrustService.trustDevice(user, deviceId);
            deviceTrusted = true;
        }

        auditService.recordSuccess(
                AuditEventType.AUTH_LOGIN_SUCCESS,
                String.valueOf(user.getId()), AuditService.ACTOR_TYPE_USER,
                String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER,
                java.util.Map.of("mfa", true, "deviceTrusted", deviceTrusted),
                auditContext);

        AuthResponseDTO response = issueToken(user, deviceId);
        if (trustGrant != null) {
            // Surface the RAW token once — the FE stores it and replays it via
            // X-Device-Trust-Token on future logins. We never persist the raw form.
            response.setDeviceTrustToken(trustGrant.rawToken());
            response.setDeviceTrustExpiresAt(trustGrant.trustedUntil());
        }
        return response;
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
        // Bump the session epoch — every service's JwtFilter compares the
        // claim's tokenVersion against users.token_version and rejects on
        // mismatch, so this is the fleet-wide kill switch for the old JWT.
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        // Force a re-login: defence-in-depth on top of the tokenVersion bump.
        //   1) Denylist the exact access token used for THIS call so the very
        //      next request is rejected at the first filter check (not the
        //      version-mismatch check) and on the gateway too.
        //   2) Revoke every refresh-token family for this user so a stolen /
        //      cached refresh can't silently mint a fresh access token under
        //      the new password.
        tokenRevocationService.revoke(token);
        refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());
        // Security hygiene: a password change must not leave a standing 2FA
        // bypass behind. Clear "remember this device" trust on every device so
        // the next login from any of them is challenged afresh. Null only in a
        // plain unit test without a Spring context.
        if (deviceTrustService != null) {
            deviceTrustService.clearTrustForUser(user.getId());
        }
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Please select at least one service to register for.");
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String b : raw) {
            if (b == null || b.isBlank()) {
                throw new RuntimeException("defaultServices values must be non-blank");
            }
            String normalised = b.trim().toLowerCase(Locale.ROOT);
            if (!Services.isKnownBundle(normalised)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "We don't recognise the service '" + b
                                + "'. Available services: " + Services.ALL_BUNDLES + ".");
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
