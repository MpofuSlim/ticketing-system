package com.innbucks.userservice.security;

import com.innbucks.userservice.entity.User;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Decides — for a given (user roles, login channel) pair — whether 2FA is
 * applicable at all, and if so whether it's REQUIRED (the user must enrol if
 * they haven't) or merely permitted (opt-in; only enforced if the user already
 * has it switched on).
 *
 * <p>The matrix:
 * <pre>
 *   USSD or WHATSAPP                    →  not applicable  (no second-factor surface)
 *   WEB or MOBILE, staff role           →  required        (forced enrolment on first login)
 *   WEB or MOBILE, CUSTOMER/TEAM_MEMBER →  opt-in          (mfaEnabled honoured if set)
 * </pre>
 *
 * <p><b>The exempt set is an allow-list, and that direction is load-bearing.</b>
 * The predicate asks "does this user hold any role that is NOT exempt", not
 * "does this user hold any exempt role". Two consequences that are the whole
 * security argument:
 * <ul>
 *   <li>A user holding TEAM_MEMBER <em>and</em> a privileged role (say an
 *       organizer who also scans at the gate, or a SUPER_ADMIN who was added
 *       to a team) is still REQUIRED. Asking the question the other way round
 *       would let anyone drop their own 2FA by acquiring a gate-staff role.</li>
 *   <li>Since V35 roles are DATA — an operator can create one at runtime — and
 *       a custom role is never in this set, so its holder is required to use
 *       2FA. That is the fail-closed default: a custom role exists to grant
 *       staff capability, and defaulting it to the exempt path would let
 *       someone opt out of MFA by creating a role.</li>
 * </ul>
 *
 * <p><b>TEAM_MEMBER is exempt from being FORCED to enrol; an already-enrolled
 * factor keeps working.</b> {@link #shouldChallenge} still honours
 * {@code mfaEnabled}, exactly as it does for a CUSTOMER — a factor that is
 * switched on is never silently ignored. Note there is currently NO
 * self-service opt-IN path: enrolment tokens are minted only in the
 * forced-enrolment login branch, so an exempt user cannot start enrolment —
 * a team member with {@code mfaEnabled=true} was force-enrolled under the
 * previous policy, not an opt-in. Such a member keeps being challenged at
 * login; because {@link #required} is now false for them, the self-service
 * {@code POST /auth/mfa/disable} guard in {@code AuthController} stops
 * refusing them, so one who still HAS their authenticator can sign in and
 * shed the factor. One who has LOST the device cannot pass the challenge and
 * needs an admin {@code POST /admin/users/{id}/mfa/reset}. V38 clears the
 * force-enrolled factors for accounts whose EVERY role is exempt — provably
 * never an opt-in, precisely because no opt-in path exists — so the gate-staff
 * population stops being challenged at deploy; nobody who could have chosen a
 * factor loses one.
 *
 * <p><b>This is the MFA predicate only.</b> "Staff" is drawn elsewhere from the
 * same "not CUSTOMER" shape — most visibly {@code AdminUserController}'s
 * {@code findAllExcludingRole(CUSTOMER)} staff listing — and TEAM_MEMBER must
 * stay staff there. The two predicates deliberately disagree now: gate staff
 * are staff, they just aren't compelled to carry an authenticator.
 */
@Component
public class MfaPolicy {

    /**
     * Roles that do not, by themselves, compel a second factor.
     *
     * <p>CUSTOMER is the original opt-in case. TEAM_MEMBER joined it because a
     * team member is gate staff — a scanner operator working a door on a
     * borrowed phone — whose entire authority is redeeming tickets for the one
     * organizer that created them. Forcing TOTP enrolment on that population
     * bought no meaningful protection and cost them entry to their own shift.
     * Adding a role here is a deliberate security decision: it must be a role
     * that cannot reach money, PII beyond its own event, or another tenant.
     */
    private static final Set<String> MFA_EXEMPT_ROLES = Set.of(
            User.Role.CUSTOMER.name(),
            User.Role.TEAM_MEMBER.name());

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
     * supports it AND they hold a role outside {@link #MFA_EXEMPT_ROLES}. The
     * caller's job is to branch on whether the user is already enrolled (and
     * trigger enrolment if not). For an opt-in CUSTOMER or TEAM_MEMBER this
     * returns false even when {@code mfaEnabled=true}; use
     * {@link #shouldChallenge} instead at the login site.
     */
    public boolean required(User user, AuthChannel channel) {
        return applicable(channel) && mfaMandatory(user);
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
        return mfaMandatory(user) || user.isMfaEnabled();
    }

    /**
     * Holds at least one role that is not MFA-exempt. Note the direction: ANY
     * non-exempt role wins, so exemption requires that EVERY role the user
     * holds is exempt.
     */
    private static boolean mfaMandatory(User user) {
        if (user.getRoles() == null || user.getRoles().isEmpty()) {
            // A roleless account can't be staff — treat as customer.
            return false;
        }
        return user.getRoles().stream().anyMatch(r -> !MFA_EXEMPT_ROLES.contains(r));
    }
}
