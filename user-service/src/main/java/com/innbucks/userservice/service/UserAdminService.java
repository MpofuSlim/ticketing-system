package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.CredentialDeliveryRequested;
import com.innbucks.userservice.event.UserDeactivatedEvent;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.util.TemporaryPasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 *
 * <p>Credential delivery used to run inline here, holding the @Transactional
 * open through three sequential outbound HTTP calls (email -> SMS -> WhatsApp)
 * and stretching the admin {@code PUT /admin/users/{id}/active} response out
 * to 30–48s — past the FE's AbortController, surfacing as a misleading
 * "Request timeout" while the DB had already committed. We now publish a
 * {@link CredentialDeliveryRequested} event and a {@code @TransactionalEventListener
 * (AFTER_COMMIT) + @Async} listener handles the fan-out off the request
 * thread (see {@code notification.CredentialDeliveryListener}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

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

        // Retry semantics: if the row already shows the requested state AND it
        // isn't a still-pending first approval, only treat as a no-op when the
        // previous credential delivery actually reached the user. The original
        // incident showed a user stuck "approved but unreachable" because every
        // channel failed and the retry button hit this short-circuit without
        // re-firing. Now: if no channel ever confirmed delivery, we rotate the
        // temp password and re-publish.
        if (user.isActive() == active && !firstApproval) {
            boolean deliveryStillPending = active
                    && user.isMustChangePassword()
                    && user.getCredentialDeliveredAt() == null;
            if (!deliveryStillPending) {
                log.info("setActive no-op userId={} active={}", id, active);
                return user;
            }
            String fresh = TemporaryPasswordGenerator.generate();
            user.setPassword(passwordEncoder.encode(fresh));
            user.setMustChangePassword(true);
            User saved = userRepository.save(user);
            log.info("setActive retry re-publishing credential delivery userId={} "
                    + "(previous attempt left credential_delivered_at NULL)", id);
            publishCredentialDelivery(saved, fresh, CredentialDeliveryRequested.Reason.APPROVAL);
            return saved;
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
            publishCredentialDelivery(saved, tempPassword,
                    CredentialDeliveryRequested.Reason.APPROVAL);
        } else if (!active) {
            // Off-thread (AFTER_COMMIT + @Async listener) so a slow notification
            // gateway can't stall — or time out — the deactivate response after
            // the row has committed. Mirrors the credential-delivery path.
            eventPublisher.publishEvent(new UserDeactivatedEvent(
                    saved.getId(), saved.getFirstName(), saved.getEmail(),
                    saved.getPhoneNumber(), saved.hasRole(User.Role.CUSTOMER)));
        }
        return saved;
    }

    /**
     * Callback target for {@code CredentialDeliveryListener} — marks the moment
     * any channel (email / SMS / WhatsApp) confirmed delivery so a retried
     * activation knows not to re-fire. Runs in its own transaction because the
     * listener executes after the original setActive() transaction has already
     * committed (and on a different thread, thanks to @Async). Failure to mark
     * is non-fatal (the timestamp is a UX hint, not an invariant) so a transient
     * DB hiccup here doesn't blow up the listener thread; logged at WARN.
     */
    @Transactional
    public void markCredentialDelivered(Long userId) {
        userRepository.findById(userId).ifPresentOrElse(u -> {
            u.setCredentialDeliveredAt(LocalDateTime.now(ZoneOffset.UTC));
            userRepository.save(u);
        }, () -> log.warn("markCredentialDelivered: user vanished between event publish "
                + "and listener callback userId={}", userId));
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
     * still single-use. {@code credentialDeliveredAt} is cleared so a successful
     * delivery on this fresh password updates the timestamp cleanly.
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
        user.setCredentialDeliveredAt(null);
        User saved = userRepository.save(user);
        log.info("Temporary password reset userId={} by={}", id, adminEmail == null ? "system" : adminEmail);

        auditService.recordSuccess(
                AuditEventType.USER_TEMP_PASSWORD_RESET,
                adminEmail == null ? "system" : adminEmail,
                adminEmail == null ? AuditService.ACTOR_TYPE_SYSTEM : AuditService.ACTOR_TYPE_USER,
                String.valueOf(saved.getId()), AuditService.TARGET_TYPE_USER,
                Map.of("targetEmail", saved.getEmail() == null ? "" : saved.getEmail()),
                auditContext == null ? AuditContext.none() : auditContext);

        publishCredentialDelivery(saved, tempPassword, CredentialDeliveryRequested.Reason.RESET);
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

    /** Fires the event the async listener picks up after this transaction commits. */
    private void publishCredentialDelivery(User user, String tempPassword,
                                           CredentialDeliveryRequested.Reason reason) {
        eventPublisher.publishEvent(new CredentialDeliveryRequested(
                user.getId(), user.getFirstName(), user.getEmail(),
                user.getPhoneNumber(), tempPassword, reason));
    }
}
