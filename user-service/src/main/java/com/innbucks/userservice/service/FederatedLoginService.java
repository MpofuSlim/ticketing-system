package com.innbucks.userservice.service;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.dto.AuthResponseDTO;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.FederationAssertionVerifier;
import com.innbucks.userservice.security.FederationAssertionVerifier.AssertionInvalidException;
import com.innbucks.userservice.security.FederationAssertionVerifier.VerifiedAssertion;
import com.innbucks.userservice.util.MsisdnCountryResolver;
import com.innbucks.userservice.util.MsisdnMasking;
import com.innbucks.userservice.util.MsisdnValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Federated customer login: trades a middleware-signed assertion for a fleet
 * CUSTOMER session ({@code POST /auth/exchange}).
 *
 * <h2>Why this exists</h2>
 * The super app's customers sign in at the InnBucks middleware, not here, and
 * the middleware's own {@code accessToken} is something this fleet can neither
 * verify (no key) nor introspect (no endpoint — measured, see InnRewards V42).
 * Every buyer-side marketplace endpoint, and everything else gated on
 * {@code hasRole('CUSTOMER')}, needs a fleet JWT. So the middleware asserts the
 * identity in a form we CAN verify — a short-lived RS256 assertion whose
 * {@code sub} is the phone — and this service issues our own session from it.
 * Merchants and admins are untouched: they still log in here with a password
 * through the admin portal.
 *
 * <h2>What it guarantees</h2>
 * <ul>
 *   <li><b>Only ever a CUSTOMER.</b> A phone that belongs to a staff account is
 *       refused outright. A middleware login can never turn into a merchant or
 *       admin session, whatever the assertion says.</li>
 *   <li><b>One use per assertion.</b> The {@code jti} is burned in Redis for the
 *       assertion's remaining lifetime (plus skew) BEFORE any account work. A
 *       captured assertion is worth at most one login, and only until it
 *       expires. If the guard cannot be consulted the login is refused — a
 *       session that could not be replay-checked is not issued.</li>
 *   <li><b>The token is a login token.</b> It comes out of
 *       {@link AuthService#issueToken}, the same mint every password login and
 *       refresh goes through, so it carries the same claims (roles, userUuid,
 *       phoneNumber, tier, permissions) and the same refresh/revocation story.
 *       Nothing downstream can tell how the customer proved themselves.</li>
 *   <li><b>The assertion is a phone proof.</b> It stamps {@code phoneVerified}
 *       exactly as an OTP does, and tells loyalty, exactly as an OTP does
 *       ({@link OtpService#finalizeVerification}) — so the customer's loyalty
 *       projections activate on first sign-in without a second SMS.</li>
 *   <li><b>Every refusal is one opaque 401.</b> Which check failed is written to
 *       the audit log, never to the caller.</li>
 * </ul>
 *
 * <h2>The account a first login creates</h2>
 * Shaped exactly like the tier-1 customer {@link OtpService} materialises from a
 * pending registration — CUSTOMER role, active, approved, placeholder name —
 * with one difference: there is no password, because the customer never chose
 * one. {@code users.password} is NOT NULL, so an unusable random hash is stored.
 * The account is passwordless in practice; the OTP-gated forgot-password flow
 * can set one later if the customer ever wants to log in here directly.
 */
@Service
@Slf4j
public class FederatedLoginService {

    static final String JTI_KEY_PREFIX = "auth:federation:jti:";
    /** Added to the jti's TTL so a replay just after {@code exp}, inside the
     *  verifier's 30s clock-skew allowance, is still caught. */
    static final Duration JTI_GRACE = Duration.ofSeconds(60);
    public static final String REJECTED_REASON = "Assertion rejected";

    private final boolean enabled;
    private final FederationAssertionVerifier verifier;
    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final LoyaltyServiceClient loyaltyServiceClient;
    private final AuditService auditService;
    private final CellAffinityChecker cellAffinityChecker;
    private final String deploymentCountry;
    private final SecureRandom random = new SecureRandom();

    public FederatedLoginService(
            @Value("${auth.federation.enabled:false}") boolean enabled,
            FederationAssertionVerifier verifier,
            StringRedisTemplate redis,
            UserRepository userRepository,
            CustomerProfileRepository customerProfileRepository,
            PasswordEncoder passwordEncoder,
            AuthService authService,
            LoyaltyServiceClient loyaltyServiceClient,
            AuditService auditService,
            CellAffinityChecker cellAffinityChecker,
            @Value("${innbucks.country:ZW}") String deploymentCountry) {
        this.enabled = enabled;
        this.verifier = verifier;
        this.redis = redis;
        this.userRepository = userRepository;
        this.customerProfileRepository = customerProfileRepository;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
        this.loyaltyServiceClient = loyaltyServiceClient;
        this.auditService = auditService;
        this.cellAffinityChecker = cellAffinityChecker;
        this.deploymentCountry = deploymentCountry;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Transactional
    public AuthResponseDTO exchange(String assertion, String deviceId, AuditContext auditContext) {
        // Off = the endpoint does not exist, same posture as loyalty's partner
        // registration. A 404 tells a prober nothing about the feature.
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        // Enabled but no key is the half-provisioned state: loud (503 + the boot
        // ERROR from FederationProvisioningCheck), never a stream of 401s that
        // reads like the middleware's bug.
        if (!verifier.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Federated login is not provisioned on this cell");
        }

        VerifiedAssertion verified;
        try {
            verified = verifier.verify(assertion);
        } catch (AssertionInvalidException e) {
            log.warn("Federated login assertion rejected: {}", e.getMessage());
            throw reject("bad_assertion", null, auditContext);
        }

        // Canonicalise to E.164 so the row, the loyalty registration and the
        // JWT's phoneNumber claim all name one spelling — the same reason
        // OtpService keys everything off the normalised number.
        String phone = MsisdnValidator.normalizeToE164(verified.phoneNumber(), deploymentCountry).orElse(null);
        if (phone == null) {
            log.warn("Federated login assertion carried an unnormalisable subject");
            throw reject("unnormalisable_phone", null, auditContext);
        }
        // Wrong-cell numbers get the same redirecting refusal every other
        // customer entry point gives them.
        cellAffinityChecker.requireDomesticMsisdn(phone);

        // Replay guard BEFORE any account work, so a replayed assertion cannot
        // even touch the row.
        claimJtiOrReject(verified, phone, auditContext);

        FoundOrCreated account = findOrCreateCustomer(phone, auditContext);

        // The assertion is a phone proof — tell loyalty, exactly as an OTP verify
        // does. Best-effort: a loyalty outage must not fail a login the customer
        // legitimately completed; their projections activate on the next call.
        if (loyaltyServiceClient != null) {
            loyaltyServiceClient.promoteUserByPhone(phone);
        }

        auditService.recordSuccess(
                AuditEventType.AUTH_FEDERATED_LOGIN_SUCCESS,
                phone, AuditService.ACTOR_TYPE_USER,
                phone, AuditService.TARGET_TYPE_USER,
                Map.of("newAccount", account.created()), auditContext);
        log.info("Federated login phone={} newAccount={}", MsisdnMasking.mask(phone), account.created());

        return authService.issueToken(account.user(), deviceId);
    }

    /**
     * One use per assertion. SETNX on a hash of the jti, expiring when the
     * assertion itself would (plus skew grace) — after that the verifier
     * refuses it on {@code exp} anyway, so the key need not outlive it.
     *
     * <p>Fails CLOSED: if Redis cannot answer, the login is refused with a
     * retryable 503 rather than issued unchecked. Redis is boot-required on
     * every cell, so this is an outage signal, not a routine path.
     */
    private void claimJtiOrReject(VerifiedAssertion verified, String phone, AuditContext auditContext) {
        Duration remaining = Duration.between(Instant.now(), verified.expiresAt());
        Duration ttl = (remaining.isNegative() ? Duration.ZERO : remaining).plus(JTI_GRACE);
        String key = JTI_KEY_PREFIX + sha256Hex(verified.jti());
        Boolean fresh;
        try {
            fresh = redis.opsForValue().setIfAbsent(key, "1", ttl);
        } catch (RuntimeException e) {
            log.error("Federated login replay guard unavailable — refusing login rather than issuing an "
                    + "unchecked session: {}", e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Login is temporarily unavailable; please try again");
        }
        if (!Boolean.TRUE.equals(fresh)) {
            log.warn("Federated login assertion REPLAYED phone={}", MsisdnMasking.mask(phone));
            throw reject("replay", phone, auditContext);
        }
    }

    private record FoundOrCreated(User user, boolean created) {}

    private FoundOrCreated findOrCreateCustomer(String phone, AuditContext auditContext) {
        Optional<User> existing = userRepository.findByPhoneNumber(phone);
        if (existing.isPresent()) {
            User user = existing.get();
            // The one thing this endpoint must never do: hand a middleware login
            // a staff session. A merchant admin's or operator's phone on their
            // account is not a customer identity.
            if (!user.hasRole(User.Role.CUSTOMER)) {
                log.warn("Federated login refused: phone belongs to a non-customer account phone={}",
                        MsisdnMasking.mask(phone));
                throw reject("not_a_customer", phone, auditContext);
            }
            if (!user.isActive()) {
                throw reject("account_inactive", phone, auditContext);
            }
            CustomerProfile profile = customerProfileRepository.findByUserId(user.getId())
                    .orElseGet(() -> newProfile(user));
            // A01/A04: refresh the proof-of-ownership window on every login, as
            // OtpService does on every verify — the tier-upgrade gate reads it.
            profile.setPhoneVerifiedAt(LocalDateTime.now(ZoneOffset.UTC));
            if (!profile.isPhoneVerified()) {
                profile.setPhoneVerified(true);
            }
            customerProfileRepository.save(profile);
            return new FoundOrCreated(user, false);
        }
        try {
            return new FoundOrCreated(createCustomer(phone), true);
        } catch (DataIntegrityViolationException raced) {
            // Two first logins for the same new phone at once: the other one
            // won the unique index. Read its row rather than failing the
            // customer whose only mistake was a double tap.
            log.info("Federated login lost a create race for phone={} — using the existing row",
                    MsisdnMasking.mask(phone));
            User user = userRepository.findByPhoneNumber(phone)
                    .orElseThrow(() -> raced);
            if (!user.hasRole(User.Role.CUSTOMER)) {
                throw reject("not_a_customer", phone, auditContext);
            }
            return new FoundOrCreated(user, false);
        }
    }

    /** Shaped exactly like OtpService.materializeOrRefreshLocalAccount, minus the chosen password. */
    private User createCustomer(String phone) {
        String homeCountry = MsisdnCountryResolver.resolve(phone).orElse(deploymentCountry);
        User user = User.builder()
                .firstName("Customer")
                .lastName("Pending")
                .phoneNumber(phone)
                // NOT NULL column, no chosen password: an unusable random hash.
                // Nobody, including this service, ever learns the plaintext.
                .password(passwordEncoder.encode(unusableSecret()))
                .roles(User.roleNames(User.Role.CUSTOMER))
                .mfaEnabled(false)
                .active(true)
                // Customers self-onboard — no SUPER_ADMIN approval step — so they
                // are approved by definition (the same comment OtpService carries).
                .approved(true)
                .homeCountry(homeCountry)
                .build();
        userRepository.save(user);
        userRepository.flush();
        customerProfileRepository.save(newProfile(user));
        log.info("Customer account created by federated login phone={} userId={}",
                MsisdnMasking.mask(phone), user.getId());
        return user;
    }

    private static CustomerProfile newProfile(User user) {
        return CustomerProfile.builder()
                .user(user)
                .registrationTier(1)
                .verified(false)
                .phoneVerified(true)
                .phoneVerifiedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private ResponseStatusException reject(String reason, String phone, AuditContext auditContext) {
        auditService.recordFailure(
                AuditEventType.AUTH_FEDERATED_LOGIN_REJECTED,
                phone, AuditService.ACTOR_TYPE_USER,
                phone, AuditService.TARGET_TYPE_USER,
                reason, Map.of(), auditContext);
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, REJECTED_REASON);
    }

    private String unusableSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** The jti is caller-chosen and unbounded; hash it so the Redis key is fixed-width. */
    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
