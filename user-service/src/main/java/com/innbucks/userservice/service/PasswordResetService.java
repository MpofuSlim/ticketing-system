package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.util.MsisdnValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
 *
 * <p><b>Staff reset by email only.</b> For an account holding a staff role
 * ({@link com.innbucks.userservice.security.StaffRoles}) both steps are no-ops by
 * PHONE — the request sends nothing, the consume step answers "Invalid or
 * expired code" — with the same 200 / 400 an unknown number gets. A phone on a
 * staff account is a takeover path: whoever holds the number (a SIM swap, or a
 * squatter's phone left on a legacy account) could otherwise set the password of
 * an account with platform-wide authority. The email reset is unchanged. Since
 * V44 the same holds for any account with a staff profile, and an INVITED staff
 * account (invite not yet redeemed) resets by NO identifier at all.
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
    private final TokenVersionBumper tokenVersionBumper;
    /** Tells whether an account holds a staff role — the phone-reset refusal. */
    private final RoleGrantGuard roleGrantGuard;
    /**
     * Staff profiles (V44): a profiled account never resets by phone either, and
     * an INVITED one never resets at all, by any identifier — until the invite is
     * redeemed the account has no password anyone chose, and a reset would be a
     * way to set one without proving the mailbox.
     */
    private final StaffEligibility staffEligibility;

    /** This cell's country pin — region hint for normalising a reset phone to
     *  E.164 so the OTP key and the user lookup match what registration stored. */
    @Value("${innbucks.country:ZW}")
    private String deploymentCountry = "ZW";

    /**
     * {@link #requestReset}'s gates read in a read-only transaction of their
     * own; the send follows it (see {@link TransactionPhases}). A setter so the
     * plain-{@code new} unit tests keep their construction; with none set the
     * gates run inline.
     */
    private TransactionTemplate readOnlyTx;

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        this.readOnlyTx = template;
    }

    /**
     * Step 1 — send the reset OTP to whichever channel the identifier names.
     * Silent no-op for unknown users, and for DEACTIVATED ones: a reset set
     * while the account is off would be live the moment an administrator
     * switched it back on — a password planted for after the reactivation,
     * chosen by whoever held the phone or mailbox at the time. The caller sees
     * the same 200 either way, so the account's state is not revealed.
     *
     * <p>Not {@code @Transactional}: every gate below is decided in a read-only
     * transaction, and the code is issued and sent AFTER it, by
     * {@link OtpService}, which commits the code before the message leaves and
     * holds no transaction across the SMS / WhatsApp / email round trip. Only
     * WHERE the send runs changed — every branch and every answer is as before.
     */
    public void requestReset(String phoneNumber, String email) {
        Identifier id = resolveIdentifier(phoneNumber, email);
        if (!Boolean.TRUE.equals(TransactionPhases.inTransaction(readOnlyTx, () -> mayReset(id)))) {
            return;
        }
        if (id.email()) {
            otpService.sendPasswordResetOtpToEmail(id.value());
        } else {
            otpService.sendPasswordResetOtpToPhone(id.value());
        }
    }

    /**
     * {@link #requestReset}'s gates. True = issue a code. Each refusal is the
     * same silent no-op it always was.
     */
    private boolean mayReset(Identifier id) {
        Optional<User> user = id.email()
                ? userRepository.findByEmail(id.value())
                : userRepository.findByPhoneNumber(id.value());
        if (user.isEmpty()) {
            log.info("Password-reset requested for unknown {} — no-op", id.email() ? "email" : "phone");
            return false;
        }
        if (deactivated(user.get())) {
            log.info("Password-reset requested for a deactivated account userId={} — no-op", user.get().getId());
            return false;
        }
        if (staffEligibility.isInvitePending(user.get())) {
            log.info("Password-reset requested for an INVITED staff account userId={} — no-op (invite pending)",
                    user.get().getId());
            return false;
        }
        if (!id.email() && (roleGrantGuard.holdsStaffRole(user.get()) || staffEligibility.isProfiled(user.get()))) {
            log.info("Password-reset requested BY PHONE for a staff account userId={} — no-op (staff reset by email)",
                    user.get().getId());
            return false;
        }
        return true;
    }

    /**
     * Customer support's reset ({@code POST /admin/support/console-users/{id}/send-password-reset}):
     * the same gates as {@link #requestReset} by EMAIL, but the code is issued in
     * the caller's transaction and emailed only after it commits
     * ({@link OtpService#sendPasswordResetOtpToEmailAfterCommit}) — the caller
     * seals the action in that transaction, and a code must not go out for an
     * action whose seal failed. Returns false (nothing issued) where
     * {@link #requestReset} would silently no-op, so the caller can say so
     * instead of claiming a code is on its way.
     *
     * @param onDelivery told, after commit, whether the email was accepted
     * @throws OtpService.OtpRateLimitException too many codes to this address recently
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean requestResetForSupport(String email, java.util.function.Consumer<Boolean> onDelivery) {
        if (email == null || email.isBlank()) return false;
        String address = email.strip();
        Optional<User> user = userRepository.findByEmail(address);
        if (user.isEmpty() || deactivated(user.get()) || staffEligibility.isInvitePending(user.get())) {
            log.info("Support password reset not issued userId={} (unknown, deactivated or invite pending)",
                    user.map(User::getId).orElse(null));
            return false;
        }
        otpService.sendPasswordResetOtpToEmailAfterCommit(address, onDelivery);
        return true;
    }

    /**
     * Approved but switched off by an administrator. A registration still
     * pending approval is also inactive, but not deactivated — its reset flow
     * is unchanged (the approval replaces the password anyway).
     */
    private static boolean deactivated(User user) {
        return user.isApproved() && !user.isActive();
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

        // A deactivated account gets the wrong-code answer, before the code is
        // even looked at: no status is revealed, and no password set now can be
        // waiting for a reactivation. (No code can have been sent since the
        // deactivation — requestReset is a no-op for it — and the deactivation
        // deleted any sent before; this closes the race between the two.)
        Optional<User> target = id.email()
                ? userRepository.findByEmail(id.value())
                : userRepository.findByPhoneNumber(id.value());
        if (target.isPresent() && deactivated(target.get())) {
            log.info("Password reset refused for a deactivated account userId={}", target.get().getId());
            throw new AuthService.PasswordChangeException("Invalid or expired code");
        }
        // A staff account never resets by phone — the same wrong-code answer,
        // before the code is looked at. No reset code is ever sent to a staff
        // phone (requestReset above), but OTP rows carry no purpose, so a code
        // sent to the same number by another flow must not work here either.
        if (target.isPresent() && !id.email()
                && (roleGrantGuard.holdsStaffRole(target.get()) || staffEligibility.isProfiled(target.get()))) {
            log.info("Password reset BY PHONE refused for a staff account userId={}", target.get().getId());
            throw new AuthService.PasswordChangeException("Invalid or expired code");
        }
        // An INVITED staff account never resets, by ANY identifier: the same
        // wrong-code answer, before the code is looked at. requestReset sends it
        // nothing, but OTP rows carry no purpose — /auth/otp/request to the same
        // address followed by this call would otherwise set a password on an
        // account whose mailbox nobody has proven.
        if (target.isPresent() && staffEligibility.isInvitePending(target.get())) {
            log.info("Password reset refused for an INVITED staff account userId={}", target.get().getId());
            throw new AuthService.PasswordChangeException("Invalid or expired code");
        }

        if (!otpService.verifyPasswordResetOtp(id.value(), otp)) {
            throw new AuthService.PasswordChangeException("Invalid or expired code");
        }
        User user = target
                // The OTP only exists for a resolved user, so this is a defensive guard.
                .orElseThrow(() -> new AuthService.PasswordChangeException("Account not found"));

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        // They proved ownership + set a new password — clear any lockout so they
        // can sign in immediately.
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        // Fleet-wide kill switch for any JWT the account may still hold: an
        // atomic bump, mirrored into the shared Redis after commit so downstream
        // services honour the reset immediately (A07 / CWE-613), not just
        // user-service's own JwtFilter.
        tokenVersionBumper.bump(user);

        // Kill every refresh-token family — a leaked/cached refresh must not
        // outlive a password reset.
        refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());

        auditService.recordSuccess(
                // A09: distinct from AUTH_PASSWORD_CHANGED (authenticated self-service
                // change) so a spike in unauthenticated OTP resets is separable.
                AuditEventType.AUTH_PASSWORD_RESET,
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
                // Best-effort E.164 so the OTP key + user lookup match what
                // registration stored. Unparseable → passed through raw, which
                // simply won't resolve to a user — keeping the flow silent (no
                // account-enumeration leak) rather than throwing.
                .map(p -> new Identifier(
                        MsisdnValidator.normalizeToE164(p, deploymentCountry).orElse(p), false))
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
