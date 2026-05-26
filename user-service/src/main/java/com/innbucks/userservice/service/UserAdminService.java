package com.innbucks.userservice.service;

import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public User setActive(Long id, boolean active) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));

        // The first activation of an account is its approval: registration left
        // only an unusable placeholder password, so assign the default now and
        // force a change on first login. The `approved` flag makes this a
        // one-shot — a later deactivate/reactivate must never reset a password
        // the user has since changed.
        boolean firstApproval = active && !user.isApproved();

        // Idempotent: a retried call sees the same outcome without a write, but
        // must never short-circuit a still-pending first approval.
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

        if (firstApproval) {
            notifyApproval(saved);
        }
        return saved;
    }

    /**
     * WhatsApp the freshly-approved user their first-time password. Best-effort:
     * a gateway failure must NOT block the approval — the account is already
     * approved and the password set, so an operator can resend (or the user can
     * use forgot-password). Never logs the password.
     */
    private void notifyApproval(User user) {
        String phone = user.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            log.warn("Approved user has no phone number; skipping first-password notification userId={}",
                    user.getId());
            return;
        }
        String message = "Your InnBucks account has been approved. Your temporary password is "
                + DEFAULT_PASSWORD
                + ". Please log in and change it immediately.";
        try {
            whatsAppNotificationClient.sendCustomNotification(phone, message);
            log.info("Approval notification sent userId={}", user.getId());
        } catch (RuntimeException ex) {
            log.warn("Approval notification failed userId={} (account still approved): {}",
                    user.getId(), ex.getMessage());
        }
    }
}
