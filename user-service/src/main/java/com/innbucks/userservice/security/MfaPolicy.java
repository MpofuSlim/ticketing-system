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
 * SUPER_ADMIN who is also an EVENT_ORGANIZER) is treated as a system user the
 * moment ANY of their roles is non-CUSTOMER.
 *
 * <p><strong>There is no exemption, for anyone.</strong> Until 2026-11-05 a
 * "gate operator" (an account holding exactly {@code TEAM_MEMBER} and no
 * permission) was never challenged. Team members were retired that day (V55):
 * gate staff are super-app scanners now, signing in to the InnBucks app as
 * customers, with no account here to challenge. Do not bring back a carve-out
 * keyed on a role name: a role's grants are editable at runtime, so a name is
 * never a statement of privilege.
 */
@Component
public class MfaPolicy {

    /**
     * Organization memberships (V39). Field-injected and optional, so
     * {@code MfaPolicyTest}'s construction sites don't widen; null there means
     * "no memberships", which is exactly the pre-V39 behaviour those tests pin.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.innbucks.userservice.repository.OrganizationMemberRepository organizationMembers;

    /**
     * Whether the account works for any organization, in any role.
     *
     * <p>Membership is business authority: a member speaks for that business on
     * every product that trusts the {@code orgId} claim, which is where a
     * seller's listings and payout destination live. That authority must never
     * ride a session that skipped the second factor. A plain CUSTOMER added to
     * an organization would otherwise do exactly that, because
     * {@link #isSystemUser} reads only the GLOBAL role set and CUSTOMER is not a
     * system role. Counting ANY membership, STAFF included, is the conservative
     * line and matches how shop staff are already treated (SHOP_USER is a
     * system role).
     */
    private boolean belongsToAnOrganization(User user) {
        return organizationMembers != null
                && user.getId() != null
                && organizationMembers.existsByUserId(user.getId());
    }

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
     * supports it AND they hold a non-CUSTOMER role (or belong to an
     * organization). The caller's job is to branch on whether the user is
     * already enrolled (and trigger enrolment if not). For an opt-in CUSTOMER,
     * this returns false even when {@code mfaEnabled=true}; use
     * {@link #shouldChallenge} instead at the login site.
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

    private boolean isSystemUser(User user) {
        if (user.getRoles() == null || user.getRoles().isEmpty()) {
            // A roleless account can't be a system user — treat as customer.
            return false;
        }
        // "System user" = holds any role other than CUSTOMER. Since V35 that
        // includes operator-created roles, which is the correct reading: a
        // custom role exists to grant staff capability, so its holder must face
        // the same MFA enrolment and challenge rules as any other staff account.
        // Defaulting a custom role to the customer path would let someone opt
        // out of MFA by creating a role.
        if (user.getRoles().stream().anyMatch(r -> !User.Role.CUSTOMER.name().equals(r))) {
            return true;
        }
        // A plain CUSTOMER is not a system user — unless they work for a
        // business (V39). See belongsToAnOrganization.
        return belongsToAnOrganization(user);
    }
}
