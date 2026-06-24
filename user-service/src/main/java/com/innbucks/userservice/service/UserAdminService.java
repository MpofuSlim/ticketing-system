package com.innbucks.userservice.service;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.util.TemporaryPasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Service-layer home for SUPER_ADMIN-scoped user-administration operations.
 *
 * <p>Pre-refactor AdminUserController#updateActiveStatus did its own
 * {@code userRepository.findById(id).orElseThrow(() -> new RuntimeException("User not found: " + id))}
 * inline — bypassing the service layer every other controller routes
 * through, surfacing a 400 instead of 404 on misses, and missing the
 * @Transactional boundary that pairs the read + write into one
 * commit. Moved here so the controller can stay thin (translate HTTP <->
 * DTO, nothing else) and so future admin operations don't keep
 * reinventing the pattern.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final WhatsAppNotificationClient whatsAppNotificationClient;
    private final SmsNotificationClient smsNotificationClient;
    private final EmailNotificationClient emailNotificationClient;
    private final AuditService auditService;

    /**
     * Backward-compatible overload used by unit tests / callers that don't have
     * an HTTP request context. The caller's identity is recorded as "SYSTEM"
     * and the audit row carries no IP / user-agent.
     */
    public User setActive(Long id, boolean active) {
        return setActive(id, active, null, AuditContext.none());
    }

    @Transactional
    public User setActive(Long id, boolean active, String adminEmail, AuditContext auditContext) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));

        // SUPER_ADMIN is the platform-owner account, seeded once by
        // BOOTSTRAP_ADMIN_PASSWORD and never modified through the admin API.
        // Disabling it would lock the platform out of itself (no other role
        // can re-enable). 403 (not 400) — this isn't a malformed request,
        // it's an action no caller is permitted to take, ever.
        if (user.hasRole(User.Role.SUPER_ADMIN)) {
            log.warn("setActive refused on SUPER_ADMIN target userId={} by={} attemptedActive={}",
                    id, adminEmail == null ? "system" : adminEmail, active);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The SUPER_ADMIN account cannot be activated or deactivated.");
        }

        // The first activation of an account is its approval: registration left
        // only an unusable placeholder password, so assign the default now and
        // force a change on first login. The `approved` flag makes this a
        // one-shot — a later deactivate/reactivate must never reset a password
        // the user has since changed.
        boolean firstApproval = active && !user.isApproved();

        // Idempotent: a retried call sees the same outcome without a write, but
        // must never short-circuit a still-pending first approval. No audit
        // row either — the no-op didn't change observable state.
        if (user.isActive() == active && !firstApproval) {
            log.info("setActive no-op userId={} active={}", id, active);
            return user;
        }

        // The generated temp password (firstApproval only) has to survive past
        // the save so it can be delivered to the user — it's never persisted in
        // plaintext, only the bcrypt hash is.
        String tempPassword = null;
        if (firstApproval) {
            tempPassword = TemporaryPasswordGenerator.generate();
            user.setPassword(passwordEncoder.encode(tempPassword));
            user.setMustChangePassword(true);
            user.setApproved(true);
            log.info("User approved, temporary password assigned userId={}", id);
        }

        user.setActive(active);
        User saved = userRepository.save(user);
        log.info("User {} userId={}", active ? "activated" : "deactivated", id);

        recordAudit(saved, firstApproval, active, adminEmail, auditContext);

        if (firstApproval) {
            notifyApproval(saved, tempPassword);
        }
        return saved;
    }

    /**
     * Mint a fresh temporary password for an already-onboarded system user and
     * re-deliver it. This is the recovery path for when the original onboarding
     * notification never reached the user — with per-user random passwords (vs.
     * the old shared {@code #Pass123}) the notification is the ONLY channel that
     * carries the credential, so a SUPER_ADMIN needs a way to re-issue it.
     *
     * <p>The old password is irretrievably bcrypt-hashed, so "resend" can only
     * mean "generate a new one" — that's why this rotates rather than re-sends.
     * The user is re-flagged {@code mustChangePassword} so the fresh value is
     * still single-use.
     *
     * <p>Refuses to act on a SUPER_ADMIN target: that account's credential is
     * owned by the {@code BOOTSTRAP_ADMIN_PASSWORD} env seed, not this
     * notification-delivered flow. Resetting it would lock the platform owner
     * out behind an SMS/email that may never arrive.
     */
    @Transactional
    public User resetTemporaryPassword(Long id, String adminEmail, AuditContext auditContext) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));

        if (user.hasRole(User.Role.SUPER_ADMIN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot reset the temporary password of a SUPER_ADMIN; that credential is "
                            + "managed via BOOTSTRAP_ADMIN_PASSWORD");
        }

        String tempPassword = TemporaryPasswordGenerator.generate();
        user.setPassword(passwordEncoder.encode(tempPassword));
        user.setMustChangePassword(true);
        User saved = userRepository.save(user);
        log.info("Temporary password reset userId={} by={}", id, adminEmail == null ? "system" : adminEmail);

        auditService.recordSuccess(
                AuditEventType.USER_TEMP_PASSWORD_RESET,
                adminEmail == null ? "system" : adminEmail,
                adminEmail == null ? AuditService.ACTOR_TYPE_SYSTEM : AuditService.ACTOR_TYPE_USER,
                String.valueOf(saved.getId()), AuditService.TARGET_TYPE_USER,
                Map.of("targetEmail", saved.getEmail() == null ? "" : saved.getEmail()),
                auditContext == null ? AuditContext.none() : auditContext);

        notifyPasswordReset(saved, tempPassword);
        return saved;
    }

    /**
     * Append an audit row covering the SUPER_ADMIN actor + the user whose
     * status changed. AuditService runs in REQUIRES_NEW + swallows exceptions
     * so a transient DB hiccup on the audit path can't reject the
     * already-committed activation; operators reading logs see the
     * {@code AUDIT_WRITE_FAILED} marker.
     */
    private void recordAudit(User target, boolean firstApproval, boolean active,
                             String adminEmail, AuditContext auditContext) {
        AuditEventType type = firstApproval
                ? AuditEventType.USER_APPROVED
                : (active ? AuditEventType.USER_ACTIVATED : AuditEventType.USER_DEACTIVATED);
        // adminEmail null when called via the no-arg overload (background /
        // tests). Surface that as actor_type=SYSTEM so the row still lands.
        String actorId = adminEmail == null ? "system" : adminEmail;
        String actorType = adminEmail == null
                ? AuditService.ACTOR_TYPE_SYSTEM
                : AuditService.ACTOR_TYPE_USER;
        auditService.recordSuccess(
                type,
                actorId, actorType,
                String.valueOf(target.getId()), AuditService.TARGET_TYPE_USER,
                Map.of(
                        "targetEmail", target.getEmail() == null ? "" : target.getEmail(),
                        "active", active,
                        "mustChangePassword", target.isMustChangePassword()),
                auditContext == null ? AuditContext.none() : auditContext);
    }

    /**
     * Notify the freshly-approved user of their first-time password. Best-effort:
     * a delivery failure must NOT block the approval — the account is already
     * approved and the password set. Email is the primary channel (system users
     * authenticate by email and a credential belongs in an inbox, not an SMS);
     * SMS then WhatsApp are the phone-based fallbacks for when no address is on
     * file or the email gateway is unreachable. Never logs the password.
     */
    private void notifyApproval(User user, String tempPassword) {
        deliverCredential(user, tempPassword,
                "Your SwiftInn account has been approved",
                "Good news — your SwiftInn account has been approved and is now active.",
                "Your SwiftInn account has been approved. Your temporary password is "
                        + tempPassword + ". Please log in and change it immediately.",
                "APPROVAL-" + user.getId());
    }

    private void notifyPasswordReset(User user, String tempPassword) {
        deliverCredential(user, tempPassword,
                "Your SwiftInn temporary password has been reset",
                "Your SwiftInn temporary password has been reset by an administrator.",
                "Your SwiftInn temporary password has been reset to "
                        + tempPassword + ". Please log in and change it immediately.",
                "PWRESET-" + user.getId());
    }

    /**
     * Deliver a freshly-generated temporary password to the user. Best-effort:
     * a delivery failure must NOT roll back the (already-committed) approval /
     * reset — the password is set either way. Email is the primary channel (a
     * credential belongs in an inbox); SMS then WhatsApp are the phone-based
     * fallbacks for when no address is on file or the email gateway is
     * unreachable. Never logs the password.
     *
     * <p>With per-user random passwords this is the ONLY channel that carries
     * the credential, so a total delivery failure leaves the user unable to log
     * in — the SUPER_ADMIN recovers them via {@code resetTemporaryPassword}.
     */
    private void deliverCredential(User user, String tempPassword,
                                   String emailSubject, String emailIntro,
                                   String smsText, String ref) {
        String email = user.getEmail();
        if (email != null && !email.isBlank()) {
            try {
                emailNotificationClient.sendEmail(
                        email, emailSubject,
                        buildCredentialText(user.getFirstName(), email, tempPassword, emailIntro),
                        ref);
                log.info("Credential email sent userId={} ref={}", user.getId(), ref);
                return;
            } catch (RuntimeException emailEx) {
                log.warn("Credential email failed userId={} ref={}, trying SMS: {}",
                        user.getId(), ref, emailEx.getMessage());
            }
        }

        String phone = user.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            log.warn("User has no reachable email or phone; skipping credential delivery userId={} ref={}",
                    user.getId(), ref);
            return;
        }
        try {
            smsNotificationClient.sendSms(phone, smsText, ref);
            log.info("Credential SMS sent userId={} ref={}", user.getId(), ref);
            return;
        } catch (RuntimeException smsEx) {
            log.warn("Credential SMS failed userId={} ref={}, trying WhatsApp: {}",
                    user.getId(), ref, smsEx.getMessage());
        }
        try {
            whatsAppNotificationClient.sendCustomNotification(phone, smsText);
            log.info("Credential WhatsApp notification sent userId={} ref={}", user.getId(), ref);
        } catch (RuntimeException ex) {
            log.warn("Credential delivery failed userId={} ref={} (account state unchanged): {}",
                    user.getId(), ref, ex.getMessage());
        }
    }

    /**
     * Renders the credential email body as plain text (the notification API has
     * no HTML mode), matching the SMS/WhatsApp standard. The temporary password
     * is a freshly-generated random value from {@link TemporaryPasswordGenerator}.
     */
    private String buildCredentialText(String firstName, String email, String tempPassword, String intro) {
        String name = (firstName != null && !firstName.isBlank()) ? firstName : "there";
        return "Hi " + name + ",\n\n"
                + intro + "\n\n"
                + "Use these credentials to sign in:\n"
                + "Username: " + email + "\n"
                + "Temporary password: " + tempPassword + "\n\n"
                + "For your security, please log in and change your password immediately.\n\n"
                + "— The SwiftInn Team";
    }
}
