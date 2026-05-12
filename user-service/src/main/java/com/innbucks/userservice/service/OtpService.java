package com.innbucks.userservice.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;

@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

    // Dev placeholder — real SMS delivery wiring will replace this.
    static final String FIXED_OTP = "000000";

    static final Duration OTP_TTL = Duration.ofMinutes(5);
    static final Duration RETRY_WINDOW = Duration.ofMinutes(10);
    static final int RETRY_LIMIT = 3;
    static final Duration LOCKOUT_DURATION = Duration.ofMinutes(30);
    static final int MAX_FAILED_VERIFICATIONS = 3;

    private final OtpRepository otpRepository;
    private final OtpRetryAttemptRepository retryRepository;
    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final PendingRegistrationRepository pendingRegistrationRepository;

    /**
     * Send an OTP with rate-limit enforcement. Used by the public /auth/otp/request endpoint
     * and by the initial tier-1 registration flow.
     */
    @Transactional
    public void sendOtp(String phoneNumber) {
        Instant now = Instant.now();
        enforceRetryQuota(phoneNumber, now);
        replaceOtp(phoneNumber, now);
        dispatch(phoneNumber, FIXED_OTP);
    }

    /**
     * Verify and consume the OTP. Returns true on success.
     * On a correct OTP: the row is deleted atomically, the retry counter is cleared,
     * and — if the phone belongs to a customer — phoneVerified is flipped to true.
     * On an incorrect OTP: increments failedAttempts and deletes the OTP once that reaches MAX_FAILED_VERIFICATIONS.
     */
    @Transactional
    public boolean verifyOtp(String phoneNumber, String code) {
        Instant now = Instant.now();
        int consumed = otpRepository.consume(phoneNumber, code, now);
        if (consumed > 0) {
            retryRepository.findByPhoneNumber(phoneNumber).ifPresent(retryRepository::delete);
            finalizeVerification(phoneNumber);
            log.info("OTP verified phone={}", phoneNumber);
            return true;
        }

        otpRepository.findByPhoneNumber(phoneNumber).ifPresent(otp -> {
            otp.setFailedAttempts(otp.getFailedAttempts() + 1);
            if (otp.getFailedAttempts() >= MAX_FAILED_VERIFICATIONS) {
                log.warn("OTP invalidated after {} failed attempts phone={}", otp.getFailedAttempts(), phoneNumber);
                otpRepository.delete(otp);
            } else {
                otpRepository.save(otp);
            }
        });
        log.warn("OTP verification failed phone={}", phoneNumber);
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

    private void replaceOtp(String phoneNumber, Instant now) {
        otpRepository.deleteByPhoneNumber(phoneNumber);
        otpRepository.flush();
        Otp otp = Otp.builder()
                .phoneNumber(phoneNumber)
                .code(FIXED_OTP)
                .expiresAt(now.plus(OTP_TTL))
                .createdAt(now)
                .failedAttempts(0)
                .build();
        otpRepository.save(otp);
    }

    private void dispatch(String phoneNumber, String code) {
        // Stub delivery — log as if it were an SMS gateway call.
        log.info("[OTP] phone={} code={} (dev fixed OTP; replace with SMS provider)", phoneNumber, code);
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
            User user = User.builder()
                    .firstName("Customer")
                    .lastName("Pending")
                    .phoneNumber(pending.getPhoneNumber())
                    .password(pending.getPasswordHash())
                    .roles(EnumSet.of(User.Role.CUSTOMER))
                    .mfaEnabled(false)
                    .active(true)
                    .build();
            userRepository.save(user);
            CustomerProfile profile = CustomerProfile.builder()
                    .user(user)
                    .registrationTier(1)
                    .verified(false)
                    .phoneVerified(true)
                    .build();
            customerProfileRepository.save(profile);
            pendingRegistrationRepository.delete(pending);
            log.info("Customer account materialised from pending registration phone={} userId={}",
                    phoneNumber, user.getId());
            return;
        }
        userRepository.findByPhoneNumber(phoneNumber)
                .filter(u -> u.hasRole(User.Role.CUSTOMER))
                .flatMap(u -> customerProfileRepository.findByUserId(u.getId()))
                .ifPresent(profile -> {
                    if (!profile.isPhoneVerified()) {
                        profile.setPhoneVerified(true);
                        customerProfileRepository.save(profile);
                    }
                });
    }

    public static class OtpRateLimitException extends RuntimeException {
        public OtpRateLimitException(String message) { super(message); }
    }
}
