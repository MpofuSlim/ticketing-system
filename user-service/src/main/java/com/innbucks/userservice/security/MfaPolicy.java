package com.innbucks.userservice.security;

import com.innbucks.userservice.entity.User;
import org.springframework.stereotype.Component;

/**
 * Decides — for a given (user roles, login channel) pair — whether 2FA is
 * applicable at all, and if so whether it's REQUIRED (the user must enrol if
 * they haven't) or merely permitted (opt-in; only enforced if the user already
 * has it switched on).
 *
 * <p>The matrix:
 * <pre>
 *   USSD or WHATSAPP             →  not applicable  (no second-factor surface)
 *   WEB or MOBILE, system user   →  required        (forced enrolment on first login)
 *   WEB or MOBILE, CUSTOMER      →  opt-in          (mfaEnabled honoured if set)
 * </pre>
 *
 * <p>System users = every Role except CUSTOMER. The roles list is taken
 * straight off the User entity, so a user with multiple roles (e.g. a
 * SUPER_ADMIN who is also an EVENT_ORGANIZER) is treated as a system user
 * the moment ANY of their roles is non-CUSTOMER.
 */
@Component
public class MfaPolicy {

    /**
     * True iff a second factor can be exchanged on this channel. Returns false
     * for USSD / WhatsApp regardless of role — those channels don't have a UI
     * for a TOTP code or a backup code.
     */
    public boolean applicable(AuthChannel channel) {
        return channel == AuthChannel.WEB || channel == AuthChannel.MOBILE;
    }

    /**
     * True iff this user MUST satisfy 2FA on this channel — i.e. the channel
     * supports it AND they hold a non-CUSTOMER role. The caller's job is to
     * branch on whether the user is already enrolled (and trigger enrolment if
     * not). For an opt-in CUSTOMER, this returns false even when
     * {@code mfaEnabled=true}; use {@link #shouldChallenge} instead at the
     * login site.
     */
    public boolean required(User user, AuthChannel channel) {
        return applicable(channel) && isSystemUser(user);
    }

    /**
     * True iff the login site should challenge the user for a TOTP/backup
     * code: either the policy requires it for this role on this channel, OR
     * the user opted in by enabling MFA themselves. False on USSD/WhatsApp
     * regardless.
     */
    public boolean shouldChallenge(User user, AuthChannel channel) {
        if (!applicable(channel)) {
            return false;
        }
        return isSystemUser(user) || user.isMfaEnabled();
    }

    private static boolean isSystemUser(User user) {
        if (user.getRoles() == null || user.getRoles().isEmpty()) {
            // A roleless account can't be a system user — treat as customer.
            return false;
        }
        return user.getRoles().stream().anyMatch(r -> r != User.Role.CUSTOMER);
    }
}
