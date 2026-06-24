package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.util.MsisdnMasking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Forgot-password flow for logged-OUT users:
 * <ol>
 *   <li>{@link #requestReset} sends a password-reset OTP to the phone (only if
 *       it belongs to a real user; always 200 to the caller so existence isn't
 *       leaked);</li>
 *   <li>{@link #resetPassword} verifies the OTP, checks the new password was
 *       entered identically twice, sets it, and forces a clean slate — revokes
 *       every refresh token, bumps {@code token_version} (so any stray JWT is
 *       rejected fleet-wide) and clears any failed-login lockout so the user can
 *       log straight in with the new password.</li>
 * </ol>
 *
 * <p>Lives outside {@link AuthService} on purpose: it needs {@link OtpService},
 * which AuthService doesn't, and keeping it separate avoids widening
 * AuthService's constructor. Reuses {@link AuthService.PasswordChangeException}
 * (mapped to 400) so the FE gets the same error shape as change-password.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    private final OtpService otpService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditService auditService;

    /** Step 1 — send the reset OTP. Silent no-op for an unknown phone. */
    @Transactional
    public void requestReset(String phoneNumber) {
        otpService.sendPasswordResetOtp(phoneNumber);
    }

    /** Step 2 — verify OTP + set the new password. */
    @Transactional
    public void resetPassword(String phoneNumber, String otp, String newPassword,
                              String confirmPassword, AuditContext auditContext) {
        // Check the confirmation BEFORE consuming the OTP, so a simple typo in
        // the confirm field doesn't burn the code (the user just resubmits).
        if (newPassword == null || !newPassword.equals(confirmPassword)) {
            throw new AuthService.PasswordChangeException("Passwords do not match");
        }
        if (!otpService.verifyPasswordResetOtp(phoneNumber, otp)) {
            throw new AuthService.PasswordChangeException("Invalid or expired code");
        }
        User user = userRepository.findByPhoneNumber(phoneNumber)
                // The OTP only exists for a real user (sendPasswordResetOtp gates
                // on that), so this is a defensive guard, not an expected path.
                .orElseThrow(() -> new AuthService.PasswordChangeException("Account not found"));

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        // Fleet-wide kill switch for any JWT the account may still hold.
        user.setTokenVersion(user.getTokenVersion() + 1);
        // The user proved phone ownership and set a new password — clear any
        // failed-login lockout so they can sign in immediately.
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        // Kill every refresh-token family — a leaked/cached refresh must not
        // outlive a password reset.
        refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());

        auditService.recordSuccess(
                AuditEventType.AUTH_PASSWORD_CHANGED,
                String.valueOf(user.getId()), AuditService.ACTOR_TYPE_USER,
                String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER,
                null, auditContext);
        log.info("Password reset via OTP userId={} phone={}", user.getId(), MsisdnMasking.mask(phoneNumber));
    }
}
