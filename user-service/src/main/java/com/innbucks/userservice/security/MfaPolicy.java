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
 *   USSD or WHATSAPP             →  not applicable  (no second-factor surface)
 *   WEB or MOBILE, system user   →  required        (forced enrolment on first login)
 *   WEB or MOBILE, CUSTOMER      →  opt-in          (mfaEnabled honoured if set)
 *   WEB or MOBILE, TEAM_MEMBER   →  opt-in          (gate-operator exemption, below)
 * </pre>
 *
 * <p>System users = every Role except CUSTOMER, minus the gate-operator
 * exemption. The roles list is taken straight off the User entity, so a user
 * with multiple roles (e.g. a SUPER_ADMIN who is also an EVENT_ORGANIZER) is
 * treated as a system user the moment ANY of their roles is non-CUSTOMER.
 *
 * <h2>The gate-operator exemption</h2>
 *
 * <p>A TEAM_MEMBER is event gate staff: they stand at a turnstile scanning
 * tickets, often on a shared handset passed between shifts, and a TOTP prompt
 * per sign-in is the wrong ergonomics for that job. So an account whose role
 * set is <em>exactly</em> {@code {TEAM_MEMBER}} takes the same opt-in path a
 * CUSTOMER takes: never forced to enrol, and challenged only if the holder
 * switched MFA on themselves.
 *
 * <p><strong>The exemption is keyed on exact set equality, NOT on "holds
 * TEAM_MEMBER", and that distinction is the whole safety of it.</strong>
 * {@link #isSystemUser} deliberately reads "holds ANY role that isn't
 * CUSTOMER" — so a contains-style exemption would invert it into "holds ANY
 * role that isn't TEAM_MEMBER", and every multi-role account containing
 * TEAM_MEMBER would stop being challenged. That is reachable in practice, by
 * at least two routes that do not require compromising anything:
 *
 * <ul>
 *   <li>{@code ServiceRequestService.approve} adds a bundle's role straight
 *       into {@code user.getRoles()}, and {@code POST /users/me/service-requests}
 *       carries no {@code @PreAuthorize} — so a team member plus one admin
 *       approval is {@code {TEAM_MEMBER, MERCHANT_ADMIN}}.</li>
 *   <li>{@code DataInitializer} MERGES {@code SUPER_ADMIN} into whatever role
 *       set already sits on the bootstrap-admin email rather than replacing it,
 *       and team-member creation accepts any unclaimed address — so
 *       {@code {SUPER_ADMIN, TEAM_MEMBER}} is reachable too.</li>
 * </ul>
 *
 * <p>Under set equality both of those keep facing a challenge, because their
 * role set is no longer exactly {@code {TEAM_MEMBER}}. The check therefore
 * fails CLOSED: any role composition we did not explicitly exempt is treated
 * as staff. Keep it that way — widening this to {@code contains(TEAM_MEMBER)}
 * turns a scanner carve-out into a fleet-wide MFA opt-out.
 *
 * <p><strong>Set equality alone is still not enough, because a role name is
 * not a fixed statement of privilege.</strong> {@code PUT /admin/roles/{name}/permissions}
 * is deliberately allowed on built-in roles, so an operator holding
 * {@code roles:write} can grant TEAM_MEMBER any concrete permission in the
 * catalog (the wildcard is refused, but {@code users:roles:write} is not). The
 * exemption therefore ALSO requires that the account resolve to no permissions
 * at all — it is keyed on being genuinely unprivileged, not on being called
 * TEAM_MEMBER. Grant gate staff real authority and they stop being exempt,
 * automatically.
 *
 * <p>Note also what the exemption is NOT: it is evaluated at login only (the
 * single call site is {@code AuthService.login}), so a session minted
 * factorless stays valid for its whole refresh chain even if the account later
 * gains a privileged second role — role mutation does not bump
 * {@code tokenVersion}. That is pre-existing behaviour for every policy
 * decision here, not something this exemption introduces, but it is the reason
 * the login-time check is strict.
 */
@Component
public class MfaPolicy {

    /**
     * Resolves an account's roles to the permissions they actually grant. The
     * gate-operator exemption consults it because a role NAME is not a stable
     * statement of privilege — see {@link #gateOperatorExempt}.
     *
     * <p>Constructor-injected rather than optional-field-injected on purpose:
     * there is no sensible null behaviour here. An exemption that could not
     * check privilege would have to refuse to exempt, and a policy object that
     * silently stops exempting is worse than one that cannot be built.
     */
    private final PermissionResolver permissionResolver;

    public MfaPolicy(PermissionResolver permissionResolver) {
        this.permissionResolver = permissionResolver;
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

    /**
     * True iff this account is a gate operator and <em>nothing else</em> — its
     * role set is exactly {@code {TEAM_MEMBER}}. Such an account is exempt from
     * forced 2FA (see the class javadoc); every other role composition,
     * including any that merely contains TEAM_MEMBER, is not.
     *
     * <p>Public so the login site can record in the audit trail that the
     * exemption is <em>why</em> no challenge was raised — a factorless staff
     * session should not be indistinguishable from a customer login after the
     * fact.
     */
    public boolean gateOperatorExempt(User user) {
        Set<String> roles = user.getRoles();
        // size()==1 is what makes this set equality rather than containment.
        // Roles are free-text since V35, so an operator-created role sitting
        // alongside TEAM_MEMBER also fails this and stays challenged.
        if (roles == null || roles.size() != 1 || !roles.contains(User.Role.TEAM_MEMBER.name())) {
            return false;
        }
        // AND the account must actually be unprivileged. The role set alone is
        // NOT sufficient: PUT /admin/roles/{name}/permissions is deliberately
        // allowed on built-in roles ("adjusting what MERCHANT_ADMIN can do is a
        // normal operation"), so TEAM_MEMBER's grants are mutable at runtime.
        // Without this check, granting TEAM_MEMBER a permission — users:roles:write,
        // say — would silently produce a privileged account that is ALSO exempt
        // from 2FA, and the set-equality test above would still pass because the
        // role set never changed.
        //
        // V35 seeds TEAM_MEMBER with no permissions and says so explicitly
        // ("TEAM_MEMBER, SHOP_USER and CUSTOMER intentionally get NOTHING here"),
        // so this holds today and self-heals if that ever stops being true: the
        // moment gate staff are given real authority they stop being exempt,
        // which is the correct direction to fail. It also covers grants made
        // outside the API (a direct SQL grant, a restore), which a guard on the
        // grant endpoint could not see.
        //
        // Cost note: this resolve() only runs for accounts that already passed
        // the single-role test, i.e. gate staff. No other login pays for it.
        return permissionResolver.resolve(roles).isEmpty();
    }

    private boolean isSystemUser(User user) {
        if (user.getRoles() == null || user.getRoles().isEmpty()) {
            // A roleless account can't be a system user — treat as customer.
            return false;
        }
        if (gateOperatorExempt(user)) {
            // Pure gate staff take the CUSTOMER opt-in path. Checked BEFORE the
            // any-non-CUSTOMER test below, which would otherwise classify them
            // as staff and force enrolment.
            return false;
        }
        // "System user" = holds any role other than CUSTOMER. Since V35 that
        // includes operator-created roles, which is the correct reading: a
        // custom role exists to grant staff capability, so its holder must face
        // the same MFA enrolment and challenge rules as any other staff account.
        // Defaulting a custom role to the customer path would let someone opt
        // out of MFA by creating a role.
        return user.getRoles().stream().anyMatch(r -> !User.Role.CUSTOMER.name().equals(r));
    }
}
