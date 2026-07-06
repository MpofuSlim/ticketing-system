package com.innbucks.userservice.service;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.util.MsisdnMasking;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.Otp;
import com.innbucks.userservice.entity.OtpRetryAttempt;
import com.innbucks.userservice.entity.PendingRegistration;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.OtpRepository;
import com.innbucks.userservice.repository.OtpRetryAttemptRepository;
import com.innbucks.userservice.repository.PendingRegistrationRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.util.MsisdnCountryResolver;
import com.innbucks.userservice.util.MsisdnValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;

@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    static final Duration OTP_TTL = Duration.ofMinutes(5);
    static final Duration RETRY_WINDOW = Duration.ofMinutes(10);
    static final int RETRY_LIMIT = 3;
    static final Duration LOCKOUT_DURATION = Duration.ofMinutes(30);
    static final int MAX_FAILED_VERIFICATIONS = 3;

    private final OtpRepository otpRepository;
    private final com.innbucks.userservice.security.OtpHasher otpHasher;
    private final OtpRetryAttemptRepository retryRepository;
    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final PendingRegistrationRepository pendingRegistrationRepository;
    private final com.innbucks.userservice.integration.LoyaltyServiceClient loyaltyServiceClient;
    private final WhatsAppNotificationClient whatsAppNotificationClient;
    private final SmsNotificationClient smsNotificationClient;
    private final EmailNotificationClient emailNotificationClient;

    /** Deployment country (ISO-3166-1 alpha-2) — single SMS gateway per
     *  deployment, so a non-domestic MSISDN must skip SMS and go straight to
     *  WhatsApp. Defaults to ZW so plain-`new` unit tests get a sensible value;
     *  Spring's @Value overrides this from INNBUCKS_COUNTRY at runtime. */
    @Value("${innbucks.country:ZW}")
    private String deploymentCountry = "ZW";

    /**
     * Send an OTP with rate-limit enforcement. Used by the public /auth/otp/request endpoint
     * and by the initial tier-1 registration flow.
     */
    @Transactional
    public void sendOtp(String phoneNumber) {
        // Key the OTP + retry rows by the canonical E.164 form so verifyOtp
        // (and the registration/materialisation that follows) resolve the same
        // row no matter how the caller spelled the number.
        phoneNumber = normalize(phoneNumber);
        Instant now = Instant.now();
        enforceRetryQuota(phoneNumber, now);
        String code = generateCode();
        replaceOtp(phoneNumber, now, code);
        dispatch(phoneNumber, code);
    }

    /**
     * Verify and consume the OTP. Returns true on success.
     * On a correct OTP: the row is deleted atomically, the retry counter is cleared,
     * and — if the phone belongs to a customer — phoneVerified is flipped to true.
     * On an incorrect OTP: increments failedAttempts and deletes the OTP once that reaches MAX_FAILED_VERIFICATIONS.
     */
    @Transactional
    public boolean verifyOtp(String phoneNumber, String code) {
        phoneNumber = normalize(phoneNumber);
        if (consumeOtp(phoneNumber, code)) {
            finalizeVerification(phoneNumber);
            log.info("OTP verified phone={}", MsisdnMasking.mask(phoneNumber));
            return true;
        }
        log.warn("OTP verification failed phone={}", MsisdnMasking.mask(phoneNumber));
        return false;
    }

    /**
     * Send a password-reset OTP to a PHONE (SMS, WhatsApp fallback). The OTP is
     * keyed by the phone. The existing-user gate lives in PasswordResetService
     * (which resolves the user first), so this just mints + dispatches. Same
     * rate-limit + single-active-OTP semantics as {@link #sendOtp}.
     */
    @Transactional
    public void sendPasswordResetOtpToPhone(String phoneNumber) {
        Instant now = Instant.now();
        enforceRetryQuota(phoneNumber, now);
        String code = generateCode();
        replaceOtp(phoneNumber, now, code);
        dispatchResetCode(phoneNumber, code);
    }

    /**
     * Send a password-reset OTP to an EMAIL. The OTP is keyed by the email
     * address (so the verify step uses the same identifier). Used when the user
     * starts the reset with their email rather than their phone — system users
     * are email-first and may have no phone. Plain-text email via the InnBucks
     * notification API.
     */
    @Transactional
    public void sendPasswordResetOtpToEmail(String email) {
        Instant now = Instant.now();
        enforceRetryQuota(email, now);
        String code = generateCode();
        replaceOtp(email, now, code);
        emailNotificationClient.sendEmail(email,
                "Your InnBucks password reset code",
                "Your password reset code is " + code + ". It expires in " + OTP_TTL.toMinutes()
                        + " minutes. If you didn't request this, you can ignore this message.",
                "PWDRESET-OTP-" + System.currentTimeMillis());
    }

    /**
     * Verify + consume a password-reset OTP. Same consume / failed-attempt
     * semantics as {@link #verifyOtp} but WITHOUT {@link #finalizeVerification}
     * — a forgot-password caller is an existing user proving phone ownership,
     * not a customer registration to materialise (or a phoneVerified flip).
     */
    @Transactional
    public boolean verifyPasswordResetOtp(String phoneNumber, String code) {
        boolean ok = consumeOtp(phoneNumber, code);
        log.info("Password-reset OTP verify phone={} ok={}", MsisdnMasking.mask(phoneNumber), ok);
        return ok;
    }

    /**
     * Atomically consume the OTP; on a wrong code, bump the failed-attempt
     * counter (invalidating the OTP after {@link #MAX_FAILED_VERIFICATIONS}).
     * Returns true iff the code matched a live OTP. Shared by the registration
     * verify and the password-reset verify.
     */
    private boolean consumeOtp(String phoneNumber, String code) {
        Instant now = Instant.now();
        // A02: rows store the HMAC of the code — hash the submitted code so the
        // consume query matches HMAC-to-HMAC (deterministic keyed digest).
        int consumed = otpRepository.consume(phoneNumber, otpHasher.hash(code), now);
        if (consumed > 0) {
            retryRepository.findByPhoneNumber(phoneNumber).ifPresent(retryRepository::delete);
            return true;
        }
        otpRepository.findByPhoneNumber(phoneNumber).ifPresent(otp -> {
            otp.setFailedAttempts(otp.getFailedAttempts() + 1);
            if (otp.getFailedAttempts() >= MAX_FAILED_VERIFICATIONS) {
                log.warn("OTP invalidated after {} failed attempts phone={}",
                        otp.getFailedAttempts(), MsisdnMasking.mask(phoneNumber));
                otpRepository.delete(otp);
            } else {
                otpRepository.save(otp);
            }
        });
        return false;
    }

    @Scheduled(fixedDelayString = "PT5M")
    @Transactional
    public void purgeExpired() {
        Instant now = Instant.now();
        int otpsRemoved = otpRepository.deleteExpired(now);
        int pendingRemoved = pendingRegistrationRepository.deleteExpired(now);
        if (otpsRemoved > 0 || pendingRemoved > 0) {
            log.info("Purged expired entries otps={} pendingRegistrations={}", otpsRemoved, pendingRemoved);
        }
    }

    private void enforceRetryQuota(String phoneNumber, Instant now) {
        OtpRetryAttempt attempt = retryRepository.findByPhoneNumber(phoneNumber)
                .orElseGet(() -> OtpRetryAttempt.builder()
                        .phoneNumber(phoneNumber)
                        .attemptCount(0)
                        .windowStartsAt(now)
                        .build());

        if (attempt.getLockedUntil() != null && attempt.getLockedUntil().isAfter(now)) {
            long minutesLeft = Math.max(1, Duration.between(now, attempt.getLockedUntil()).toMinutes());
            throw new OtpRateLimitException(
                    "Too many OTP requests. Try again in " + minutesLeft + " minute(s).");
        }

        // Window has rolled over — start fresh.
        if (attempt.getWindowStartsAt().plus(RETRY_WINDOW).isBefore(now)) {
            attempt.setAttemptCount(0);
            attempt.setWindowStartsAt(now);
            attempt.setLockedUntil(null);
        }

        attempt.setAttemptCount(attempt.getAttemptCount() + 1);
        if (attempt.getAttemptCount() > RETRY_LIMIT) {
            attempt.setLockedUntil(now.plus(LOCKOUT_DURATION));
            retryRepository.save(attempt);
            throw new OtpRateLimitException(
                    "Too many OTP requests. Try again in " + LOCKOUT_DURATION.toMinutes() + " minute(s).");
        }
        retryRepository.save(attempt);
    }

    private void replaceOtp(String phoneNumber, Instant now, String code) {
        otpRepository.deleteByPhoneNumber(phoneNumber);
        otpRepository.flush();
        Otp otp = Otp.builder()
                .phoneNumber(phoneNumber)
                // A02: never persist the raw code — store its keyed HMAC.
                .code(otpHasher.hash(code))
                .expiresAt(now.plus(OTP_TTL))
                .createdAt(now)
                .failedAttempts(0)
                .build();
        otpRepository.save(otp);
    }

    private static String generateCode() {
        // Cryptographically-strong 6-digit code, zero-padded (000000–999999).
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private void dispatch(String phoneNumber, String code) {
        dispatchMessage(phoneNumber, "Your InnBucks verification code is " + code
                + ". It expires in " + OTP_TTL.toMinutes()
                + " minutes. Do not share this code with anyone.");
    }

    private void dispatchResetCode(String phoneNumber, String code) {
        dispatchMessage(phoneNumber, "Your InnBucks password reset code is " + code
                + ". It expires in " + OTP_TTL.toMinutes()
                + " minutes. If you didn't request this, you can ignore this message.");
    }

    private void dispatchMessage(String phoneNumber, String message) {
        // Runs inside the @Transactional send boundary: if delivery fails,
        // the persisted OTP and retry-counter roll back for a clean retry.
        // Never log the code itself.

        // Country-aware channel routing. The InnBucks SMS gateway is currently
        // a single per-country adapter (the ZW deployment ships SMS via the ZW
        // gateway; KE has its own); it accepts a foreign-prefix number with a
        // 2xx and silently drops it downstream, so we'd "succeed" without ever
        // reaching the customer. Skip SMS entirely for any number that isn't
        // domestic to this deployment, and go straight to WhatsApp — which IS
        // global and works on any E.164 number.
        boolean isDomestic = isDomesticMsisdn(phoneNumber);
        if (!isDomestic) {
            log.info("[OTP] foreign MSISDN on {} deployment — routing to WhatsApp directly phone={}",
                    deploymentCountry, MsisdnMasking.mask(phoneNumber));
            whatsAppNotificationClient.sendCustomNotification(phoneNumber, message);
            log.info("[OTP] dispatched via WhatsApp (foreign MSISDN) to phone={}",
                    MsisdnMasking.mask(phoneNumber));
            return;
        }

        try {
            smsNotificationClient.sendSms(phoneNumber, message, "OTP-" + System.currentTimeMillis());
            log.info("[OTP] dispatched via SMS to phone={}", MsisdnMasking.mask(phoneNumber));
        } catch (NotificationDeliveryException smsEx) {
            log.warn("[OTP] SMS delivery failed for phone={}, falling back to WhatsApp: {}",
                    MsisdnMasking.mask(phoneNumber), smsEx.getMessage());
            whatsAppNotificationClient.sendCustomNotification(phoneNumber, message);
            log.info("[OTP] dispatched via WhatsApp fallback to phone={}", MsisdnMasking.mask(phoneNumber));
        }
    }

    /**
     * True when the MSISDN's dialling prefix resolves to the deployment's
     * country. An unresolvable MSISDN (unknown prefix) is treated as
     * non-domestic — safer to route to WhatsApp than to claim a successful
     * SMS that the local gateway silently drops.
     */
    /**
     * Canonicalise a phone-channel MSISDN to E.164 against this cell's country,
     * best-effort: an unparseable value passes through unchanged so we never
     * throw here (the generic /auth/otp endpoints accept a number and either
     * deliver or don't). Idempotent, so callers that already normalised (e.g.
     * registerTier1) are unaffected. NOT applied to the reset methods — those
     * receive an already-resolved identifier that may be an email.
     */
    private String normalize(String phoneNumber) {
        return MsisdnValidator.normalizeToE164(phoneNumber, deploymentCountry).orElse(phoneNumber);
    }

    private boolean isDomesticMsisdn(String phoneNumber) {
        return MsisdnCountryResolver.resolve(phoneNumber)
                .map(c -> c.equalsIgnoreCase(deploymentCountry))
                .orElse(false);
    }

    /**
     * Runs after a successful OTP consume.
     *
     * <p>If a pending_registrations row exists for the phone (the common path, set up by tier 1
     * customer registration), materialise the {@link User} + {@link CustomerProfile} from it and
     * drop the pending row. If the phone already belongs to a customer, just flip
     * {@code phoneVerified = true}.
     */
    private void finalizeVerification(String phoneNumber) {
        var pendingOpt = pendingRegistrationRepository.findByPhoneNumber(phoneNumber);
        if (pendingOpt.isPresent()) {
            PendingRegistration pending = pendingOpt.get();
            // CUSTOMERS are auto-active once they've proven phone ownership via
            // OTP — there's no human approver in the customer onboarding flow,
            // unlike business roles (EVENT_ORGANIZER / MERCHANT_ADMIN) which
            // stay inactive until a SUPER_ADMIN approves them via /admin/users.
            // Step 4: persist home_country on the row at materialisation
            // time. Derived from the MSISDN prefix via MsisdnCountryResolver;
            // foreign numbers fall back to the deployment country so the new
            // NOT NULL column is satisfied without rejecting any historical
            // registration shape.
            String homeCountry = MsisdnCountryResolver.resolve(pending.getPhoneNumber())
                    .orElse(deploymentCountry);
            User user = User.builder()
                    .firstName("Customer")
                    .lastName("Pending")
                    .phoneNumber(pending.getPhoneNumber())
                    .password(pending.getPasswordHash())
                    .roles(EnumSet.of(User.Role.CUSTOMER))
                    .mfaEnabled(false)
                    .active(true)
                    // Customers self-onboard via OTP — there is no SUPER_ADMIN
                    // approval step, so they are approved by definition. Set it
                    // explicitly so login's pending-approval check (!active &&
                    // !approved) never mistakes a customer for a pending signup.
                    .approved(true)
                    .homeCountry(homeCountry)
                    .build();
            userRepository.save(user);
            CustomerProfile profile = CustomerProfile.builder()
                    .user(user)
                    .registrationTier(1)
                    .verified(false)
                    .phoneVerified(true)
                    // A01/A04: stamp the proof-of-ownership time so the tier2/3/4
                    // gate sees a recent verification for this fresh account.
                    .phoneVerifiedAt(LocalDateTime.now(ZoneOffset.UTC))
                    .build();
            customerProfileRepository.save(profile);
            pendingRegistrationRepository.delete(pending);
            log.info("Customer account materialised from pending registration phone={} userId={}",
                    MsisdnMasking.mask(phoneNumber), user.getId());
            // Tell loyalty-service this phone has now registered so every
            // PENDING LoyaltyUser row matching it (created by prior voucher
            // issues, transactions, P2P transfers) flips ACTIVE and the
            // customer can finally spend whatever was accrued for them.
            // Best-effort: webhook failure does NOT roll back registration.
            loyaltyServiceClient.promoteUserByPhone(phoneNumber);
            return;
        }
        userRepository.findByPhoneNumber(phoneNumber)
                .filter(u -> u.hasRole(User.Role.CUSTOMER))
                .flatMap(u -> customerProfileRepository.findByUserId(u.getId()))
                .ifPresent(profile -> {
                    // A01/A04: refresh the proof-of-ownership window on EVERY
                    // successful verify — even a re-verify of an already-verified
                    // phone — so the tier2/3/4 gate sees a fresh timestamp.
                    profile.setPhoneVerifiedAt(LocalDateTime.now(ZoneOffset.UTC));
                    if (!profile.isPhoneVerified()) {
                        profile.setPhoneVerified(true);
                        // First time this phone has been verified — same
                        // signal to loyalty as the pending-registration path.
                        loyaltyServiceClient.promoteUserByPhone(phoneNumber);
                    }
                    customerProfileRepository.save(profile);
                });
    }

    public static class OtpRateLimitException extends RuntimeException {
        public OtpRateLimitException(String message) { super(message); }
    }
}
