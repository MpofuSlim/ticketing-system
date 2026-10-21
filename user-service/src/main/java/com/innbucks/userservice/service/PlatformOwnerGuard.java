package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * The ONE answer to "is the caller the seeded platform owner?" (V52) — the
 * only account that may promote another account to SUPER_ADMIN or demote one.
 *
 * <p>Owner decision (2026-10-21): there may be more than one SUPER_ADMIN, and a
 * deputy can do everything the owner can EXCEPT promote or demote. A deputy
 * holds the same role name and the same {@code *} wildcard, so neither the
 * token's roles nor its {@code perms} can tell the two apart — and neither can
 * an email compared with {@code BOOTSTRAP_ADMIN_EMAIL}, which is configuration
 * and can be rotated. The answer is the caller's LIVE row: {@code users.platform_owner}
 * (stamped once by {@code DataInitializer}, never by a request), the account
 * still {@code active}, and still holding SUPER_ADMIN. Never a token claim.
 *
 * <p>Fails closed: an unknown subject, a deactivated account, a row without the
 * flag or without the role are all the same {@code 403 platform_owner_only},
 * and every refusal is written as a {@code STAFF_GRANT_REFUSED} FAILURE row
 * ({@link RoleGrantGuard#recordRefusal}) — a deputy trying to promote is
 * exactly the attempt an insider-threat review looks for.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformOwnerGuard {

    private final UserRepository userRepository;
    private final RoleGrantGuard roleGrantGuard;

    /**
     * The owner's account, when {@code subject} (the JWT subject: the email, or
     * the phone for an account with none) names it; 403
     * {@code platform_owner_only} otherwise.
     *
     * @param action a short tag for the refusal's audit row and log line
     *               (e.g. {@code super_admin_promote})
     */
    public User requireOwner(String subject, String action) {
        Optional<User> caller = resolve(subject);
        if (caller.isPresent() && isLiveOwner(caller.get())) {
            return caller.get();
        }
        log.warn("Refused {}: caller={} is not the platform owner", action, subject);
        roleGrantGuard.recordRefusal(subject, null, StaffPolicyException.PLATFORM_OWNER_ONLY,
                Map.of("action", action));
        throw StaffPolicyException.platformOwnerOnly();
    }

    /** The same decision, answered rather than thrown. Writes nothing. */
    public boolean isOwner(String subject) {
        return resolve(subject).map(PlatformOwnerGuard::isLiveOwner).orElse(false);
    }

    /** The flag, an active account, and the role — all three, read from the row. */
    static boolean isLiveOwner(User user) {
        return user.isPlatformOwner() && user.isActive() && user.hasRole(User.Role.SUPER_ADMIN);
    }

    private Optional<User> resolve(String subject) {
        if (subject == null || subject.isBlank()) return Optional.empty();
        return subject.contains("@")
                ? userRepository.findByEmail(subject)
                : userRepository.findByPhoneNumber(subject);
    }
}
