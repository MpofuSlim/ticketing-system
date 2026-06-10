package com.innbucks.userservice.service;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

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

    // Shared default assigned to a system user when their account is approved.
    // The user is forced to change it on first login (must_change_password).
    private static final String DEFAULT_PASSWORD = "#Pass123";

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

        if (firstApproval) {
            user.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
            user.setMustChangePassword(true);
            user.setApproved(true);
            log.info("User approved, default password assigned userId={}", id);
        }

        user.setActive(active);
        User saved = userRepository.save(user);
        log.info("User {} userId={}", active ? "activated" : "deactivated", id);

        recordAudit(saved, firstApproval, active, adminEmail, auditContext);

        if (firstApproval) {
            notifyApproval(saved);
        }
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
    private void notifyApproval(User user) {
        String message = "Your InnBucks account has been approved. Your temporary password is "
                + DEFAULT_PASSWORD
                + ". Please log in and change it immediately.";

        // Email primary — the credential belongs in an inbox.
        String email = user.getEmail();
        if (email != null && !email.isBlank()) {
            try {
                emailNotificationClient.sendEmail(
                        email,
                        "Your InnBucks account has been approved",
                        buildApprovalHtml(user.getFirstName(), email),
                        "APPROVAL-" + user.getId());
                log.info("Approval email sent userId={}", user.getId());
                return;
            } catch (RuntimeException emailEx) {
                log.warn("Approval email failed userId={}, trying SMS: {}", user.getId(), emailEx.getMessage());
            }
        }

        // Phone-based fallback: SMS first, then WhatsApp.
        String phone = user.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            log.warn("Approved user has no reachable email or phone; skipping first-password notification userId={}",
                    user.getId());
            return;
        }
        try {
            smsNotificationClient.sendSms(phone, message, "APPROVAL-" + user.getId());
            log.info("Approval SMS sent userId={}", user.getId());
            return;
        } catch (RuntimeException smsEx) {
            log.warn("Approval SMS failed userId={}, trying WhatsApp: {}", user.getId(), smsEx.getMessage());
        }
        try {
            whatsAppNotificationClient.sendCustomNotification(phone, message);
            log.info("Approval WhatsApp notification sent userId={}", user.getId());
        } catch (RuntimeException ex) {
            log.warn("Approval notification failed userId={} (account still approved): {}",
                    user.getId(), ex.getMessage());
        }
    }

    /**
     * Renders the approval email body. Dynamic values are HTML-escaped; the
     * temporary password is a fixed internal constant, not caller-supplied.
     */
    private String buildApprovalHtml(String firstName, String email) {
        String name = (firstName != null && !firstName.isBlank())
                ? HtmlUtils.htmlEscape(firstName) : "there";
        return "<p>Hi " + name + ",</p>"
                + "<p>Good news — your InnBucks account has been approved and is now active.</p>"
                + "<p>Use these credentials to sign in:</p>"
                + "<ul>"
                + "<li><strong>Username:</strong> " + HtmlUtils.htmlEscape(email) + "</li>"
                + "<li><strong>Temporary password:</strong> " + DEFAULT_PASSWORD + "</li>"
                + "</ul>"
                + "<p>For your security, please log in and change your password immediately.</p>"
                + "<p>— The InnBucks Team</p>";
    }
}
