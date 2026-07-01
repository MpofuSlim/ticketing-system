package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Forgot-password flow for logged-OUT users, started by EITHER phone number OR
 * email:
 * <ol>
 *   <li>{@link #requestReset} resolves the user by the supplied identifier and
 *       sends a reset OTP on the matching channel — email code to an email,
 *       SMS/WhatsApp code to a phone. Always silent for an unknown identifier
 *       (no SMS/email spam, no account enumeration); the controller always
 *       returns 200.</li>
 *   <li>{@link #resetPassword} verifies the OTP (keyed by the same identifier),
 *       checks the new password was entered identically twice, sets it, and
 *       forces a clean slate — revokes every refresh token, bumps
 *       {@code token_version} and clears any failed-login lockout.</li>
 * </ol>
 *
 * <p>Lives outside {@link AuthService} on purpose (it needs {@link OtpService}
 * and AuthService is widely unit-constructed). Reuses
 * {@link AuthService.PasswordChangeException} (mapped to 400) for a consistent
 * FE error shape. When both identifiers are supplied, email wins (system users
 * are email-first).
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
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** Step 1 — send the reset OTP to whichever channel the identifier names. Silent no-op for unknown users. */
    @Transactional
    public void requestReset(String phoneNumber, String email) {
        Identifier id = resolveIdentifier(phoneNumber, email);
        if (id.email()) {
            if (userRepository.findByEmail(id.value()).isPresent()) {
                otpService.sendPasswordResetOtpToEmail(id.value());
            } else {
                log.info("Password-reset requested for unknown email — no-op");
            }
        } else {
            if (userRepository.findByPhoneNumber(id.value()).isPresent()) {
                otpService.sendPasswordResetOtpToPhone(id.value());
            } else {
                log.info("Password-reset requested for unknown phone — no-op");
            }
        }
    }

    /** Step 2 — verify OTP + set the new password. */
    @Transactional
    public void resetPassword(String phoneNumber, String email, String otp,
                              String newPassword, String confirmPassword, AuditContext auditContext) {
        // Check the confirmation BEFORE consuming the OTP, so a typo in the
        // confirm field doesn't burn the code (the user just resubmits).
        if (newPassword == null || !newPassword.equals(confirmPassword)) {
            throw new AuthService.PasswordChangeException("Passwords do not match");
        }
        Identifier id = resolveIdentifier(phoneNumber, email);

        if (!otpService.verifyPasswordResetOtp(id.value(), otp)) {
            throw new AuthService.PasswordChangeException("Invalid or expired code");
        }
        User user = (id.email()
                ? userRepository.findByEmail(id.value())
                : userRepository.findByPhoneNumber(id.value()))
                // The OTP only exists for a resolved user, so this is a defensive guard.
                .orElseThrow(() -> new AuthService.PasswordChangeException("Account not found"));

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        // Fleet-wide kill switch for any JWT the account may still hold.
        user.setTokenVersion(user.getTokenVersion() + 1);
        // They proved ownership + set a new password — clear any lockout so they
        // can sign in immediately.
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
        log.info("Password reset via OTP userId={} via={}", user.getId(), id.email() ? "email" : "phone");
        // Security alert (email + SMS): if it wasn't them, a leaked OTP just
        // changed their password and they must act.
        eventPublisher.publishEvent(new com.innbucks.userservice.event.AccountSecurityAlertEvent(
                user.getId(), user.getFirstName(), user.getEmail(), user.getPhoneNumber(),
                user.hasRole(User.Role.CUSTOMER),
                com.innbucks.userservice.event.AccountSecurityAlertEvent.Type.PASSWORD_RESET));
    }

    /** Pick the identifier to use: email if present, else phone. Neither → 400. */
    private Identifier resolveIdentifier(String phoneNumber, String email) {
        Optional<String> emailId = trimToOptional(email);
        if (emailId.isPresent()) {
            return new Identifier(emailId.get(), true);
        }
        return trimToOptional(phoneNumber)
                .map(p -> new Identifier(p, false))
                .orElseThrow(() -> new AuthService.PasswordChangeException(
                        "Provide a phone number or email to reset your password"));
    }

    private static Optional<String> trimToOptional(String s) {
        return (s == null || s.isBlank()) ? Optional.empty() : Optional.of(s.trim());
    }

    /** The chosen reset identifier and whether it's an email (vs phone). */
    private record Identifier(String value, boolean email) {
    }
}
