package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.StaffEmailPolicy;
import com.innbucks.userservice.security.StaffRoles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The staff-account rules (V44) — who may newly hold staff authority, and what
 * a staff account may never become. One component so the create endpoint, every
 * role grant, every role edit, the organization and service-request writers and
 * the token mint all ask the same questions and get the same answers.
 *
 * <h2>Definitions</h2>
 * <ul>
 *   <li><b>Staff account</b>: holds at least one staff role ({@link StaffRoles}),
 *       or has a {@code staff_profiles} row.</li>
 *   <li><b>Staff-eligible</b> — the requirement to NEWLY hold staff authority.
 *       SUPER_ADMIN is exempt from every check. Anyone else needs all of: the
 *       email passes {@link StaffEmailPolicy} under the current configuration;
 *       {@code users.email_verified_at} is set; a profile exists and its invite
 *       was accepted.</li>
 *   <li><b>Accept-eligible</b> ({@link #acceptIneligibility}) — what redeeming
 *       an invite requires. It deliberately does NOT require verification,
 *       because verification is what redeeming establishes.</li>
 * </ul>
 *
 * <p>Every refusal is written as a {@code STAFF_GRANT_REFUSED} FAILURE row
 * through {@link RoleGrantGuard#recordRefusal} before it is thrown.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaffEligibility {

    /** Why an account is not staff-eligible; the value is the metric / audit tag. */
    public enum Ineligibility {
        OFF_DOMAIN("off_domain"),
        UNVERIFIED("unverified"),
        NO_PROFILE("no_profile");

        private final String tag;

        Ineligibility(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /** Most holders a {@code staff_holders_ineligible} refusal names. */
    static final int SAMPLE_LIMIT = 20;

    private final StaffProfileRepository profiles;
    private final StaffEmailPolicy emailPolicy;
    private final RoleGrantGuard roleGrantGuard;
    private final OrganizationMemberRepository members;
    private final OrganizationRepository organizations;
    private final RoleRepository roles;
    private final UserRepository users;

    // ------------------------------------------------------------------
    // Questions
    // ------------------------------------------------------------------

    public Optional<StaffProfile> profileOf(User user) {
        if (user == null || user.getId() == null) return Optional.empty();
        return profiles.findById(user.getId());
    }

    public boolean isProfiled(User user) {
        return profileOf(user).isPresent();
    }

    /** Profiled and the invite not yet redeemed: the account cannot sign in. */
    public boolean isInvitePending(User user) {
        return profileOf(user).map(StaffProfile::isInvitePending).orElse(false);
    }

    /** Holds a staff role, or has a staff profile. */
    public boolean isStaffAccount(User user) {
        if (user == null) return false;
        return roleGrantGuard.holdsStaffRole(user) || isProfiled(user);
    }

    /** Member of at least one ACTIVE organization. */
    public boolean hasActiveOrganization(User user) {
        if (user == null || user.getId() == null) return false;
        List<OrganizationMember> mine = members.findByUserId(user.getId());
        if (mine.isEmpty()) return false;
        return organizations.findAllById(mine.stream().map(OrganizationMember::getOrganizationId).toList())
                .stream().anyMatch(o -> o.getStatus() == Organization.Status.ACTIVE);
    }

    /** Why {@code user} may not newly hold staff authority; empty when eligible (or SUPER_ADMIN). */
    public Optional<Ineligibility> ineligibility(User user) {
        return ineligibility(user, profileOf(user).orElse(null));
    }

    Optional<Ineligibility> ineligibility(User user, StaffProfile profile) {
        if (user == null) return Optional.of(Ineligibility.NO_PROFILE);
        if (user.hasRole(User.Role.SUPER_ADMIN)) return Optional.empty();
        if (!emailPolicy.evaluate(user.getEmail()).ok()) return Optional.of(Ineligibility.OFF_DOMAIN);
        if (user.getEmailVerifiedAt() == null) return Optional.of(Ineligibility.UNVERIFIED);
        if (profile == null || profile.getInviteAcceptedAt() == null) return Optional.of(Ineligibility.NO_PROFILE);
        return Optional.empty();
    }

    /**
     * Why redeeming {@code invite}'s link for {@code user} must be refused; empty
     * when it may proceed. The account is active; a profile exists with the
     * invite not yet accepted; the invite was sent to the address the account
     * still has; the domain is allowed under current config; every role is a
     * staff role; and there is no ACTIVE organization membership.
     */
    public Optional<String> acceptIneligibility(User user, String sentToEmail) {
        if (user == null || !user.isActive()) return Optional.of("account_inactive");
        Optional<StaffProfile> profile = profileOf(user);
        if (profile.isEmpty()) return Optional.of("no_profile");
        if (!profile.get().isInvitePending()) return Optional.of("already_accepted");
        if (sentToEmail == null || !sentToEmail.equals(user.getEmail())) return Optional.of("email_changed");
        if (!emailPolicy.evaluate(user.getEmail()).ok()) return Optional.of("off_domain");
        if (!roleGrantGuard.nonStaffRoles(user).isEmpty()) return Optional.of("holds_non_staff_roles");
        if (hasActiveOrganization(user)) return Optional.of("organization_member");
        return Optional.empty();
    }

    /**
     * Why a legacy account (no profile) cannot be adopted, or empty when it can:
     * active, not SUPER_ADMIN, holds at least one staff role and ONLY staff
     * roles, on an allowed domain, with no ACTIVE organization membership. Not
     * meaningful for a profiled account.
     */
    public Optional<String> adoptionBlocker(User user) {
        if (!roleGrantGuard.nonStaffRoles(user).isEmpty()) {
            return Optional.of(StaffPolicyException.ADOPTION_HOLDS_NON_STAFF_ROLES);
        }
        if (hasActiveOrganization(user)) return Optional.of(StaffPolicyException.ADOPTION_ORGANIZATION_MEMBER);
        if (!emailPolicy.evaluate(user.getEmail()).ok()) return Optional.of(StaffPolicyException.ADOPTION_OFF_DOMAIN);
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Guards (each refusal audited, then thrown)
    // ------------------------------------------------------------------

    /**
     * A staff role is being ADDED to {@code target}: it must be staff-eligible.
     * 503 {@code staff_domains_unconfigured} on a cell naming no staff domain;
     * 400 {@code email_domain_not_allowed} for an off-domain address; 400
     * {@code staff_email_unverified} ("adopt first") otherwise.
     */
    public void requireEligibleForStaffGrant(User target, String actor, Collection<String> staffRolesAdded) {
        if (target.hasRole(User.Role.SUPER_ADMIN)) return;
        emailPolicy.requireConfigured();
        Optional<Ineligibility> why = ineligibility(target);
        if (why.isEmpty()) return;
        roleGrantGuard.recordRefusal(actor, target, "staff_" + why.get().tag(),
                Map.of("roles", new TreeSet<>(staffRolesAdded)));
        log.warn("Refused staff role grant userId={} roles={} reason={} by={}",
                target.getId(), staffRolesAdded, why.get().tag(), actor);
        throw why.get() == Ineligibility.OFF_DOMAIN
                ? emailPolicy.domainNotAllowed()
                : StaffPolicyException.staffEmailUnverified();
    }

    /**
     * A profiled account may hold only staff roles: 400 {@code role_not_assignable}
     * naming each non-staff role in {@code requested} ({@code not_a_staff_role}).
     */
    public void requireOnlyStaffRolesForProfiled(User target, Collection<String> requested, String actor) {
        if (!isProfiled(target)) return;
        Map<String, Role> rows = roles.findAllByNameIn(requested).stream()
                .collect(Collectors.toMap(Role::getName, Function.identity(), (a, b) -> a));
        Map<String, String> refused = new TreeMap<>();
        for (String name : requested) {
            Role row = rows.get(name);
            if (!StaffRoles.isNamed(name) && !StaffRoles.isStaffRole(row)) {
                refused.put(name, StaffPolicyException.REASON_NOT_A_STAFF_ROLE);
            }
        }
        if (refused.isEmpty()) return;
        roleGrantGuard.recordRefusal(actor, target, StaffPolicyException.ROLE_NOT_ASSIGNABLE,
                Map.of("roles", new LinkedHashMap<>(refused)));
        throw StaffPolicyException.roleNotAssignable(new LinkedHashMap<>(refused));
    }

    /**
     * {@code target} is a staff account and so may never join a business or
     * request products — 409 {@code staff_account_not_eligible}. A support
     * agent must never also be a merchant or a seller.
     */
    public void requireNotStaffAccount(User target, String actor, String site) {
        requireNotStaffAccount(target, actor, site, null);
    }

    /**
     * As {@link #requireNotStaffAccount(User, String, String)}, with a message
     * that fits the site (tier-2 is not "joining a business"); a null message
     * is the default one.
     */
    public void requireNotStaffAccount(User target, String actor, String site, String message) {
        if (target == null || !isStaffAccount(target)) return;
        roleGrantGuard.recordRefusal(actor, target, StaffPolicyException.STAFF_ACCOUNT_NOT_ELIGIBLE,
                Map.of("site", site));
        log.warn("Refused {} for a staff account userId={} by={}", site, target.getId(), actor);
        throw StaffPolicyException.staffAccountNotEligible(message);
    }

    /**
     * For a path where a BUSINESS names a person by email (adding a member):
     * true when {@code person} is a staff account. The refusal is audited
     * ({@code STAFF_GRANT_REFUSED}) and the caller must answer exactly as it
     * does for an address with no account — otherwise a business owner could
     * probe which accounts are InnBucks staff. An address on a staff domain is
     * NOT refused for that alone: InnBucks addresses are ordinary business and
     * customer addresses (owner decision, 2026-10-08).
     */
    public boolean refusedAsUnknownAccount(User person, String actor, String site) {
        if (person == null || !isStaffAccount(person)) return false;
        roleGrantGuard.recordRefusal(actor, person, StaffPolicyException.STAFF_ACCOUNT_NOT_ELIGIBLE,
                Map.of("site", site, "answeredAs", "account_not_found"));
        log.warn("Refused {} for a staff account (answered as unknown) by={}", site, actor);
        return true;
    }

    /**
     * A role edit or create that ADDS a PLATFORM code (or turns a non-staff role
     * into a staff one): every current holder but SUPER_ADMIN must be
     * staff-eligible — 400 {@code staff_holders_ineligible} with the count, the
     * count per reason and up to 20 holders' userUuids. Holders are read from
     * {@code user_roles}, which has no FK to {@code roles}, so an orphan string
     * counts as a holder.
     */
    public void requireHoldersEligible(String roleName, String actor) {
        List<Long> ids = roles.findHolderIds(roleName);
        if (ids.isEmpty()) return;
        emailPolicy.requireConfigured();
        List<User> holders = users.findAllById(ids);
        Map<Long, StaffProfile> profileById = profiles.findAllByUserIdIn(ids).stream()
                .collect(Collectors.toMap(StaffProfile::getUserId, Function.identity()));
        Map<String, Integer> byReason = new TreeMap<>();
        List<String> sample = new ArrayList<>();
        int count = 0;
        for (User holder : holders.stream().sorted(java.util.Comparator.comparing(User::getId)).toList()) {
            Optional<Ineligibility> why = ineligibility(holder, profileById.get(holder.getId()));
            if (why.isEmpty()) continue;
            count++;
            byReason.merge(why.get().tag(), 1, Integer::sum);
            if (sample.size() < SAMPLE_LIMIT) sample.add(String.valueOf(holder.getUserUuid()));
        }
        if (count == 0) return;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("role", roleName);
        detail.put("ineligibleHolders", count);
        detail.put("byReason", byReason);
        roleGrantGuard.recordRefusal(actor, null, StaffPolicyException.STAFF_HOLDERS_INELIGIBLE, detail);
        log.warn("Refused a staff-making edit of role={} — {} ineligible holder(s) {} by={}",
                roleName, count, byReason, actor);
        throw StaffPolicyException.staffHoldersIneligible(count, byReason, sample);
    }

    /** True when adding {@code added} to a role currently granting {@code previous} needs {@link #requireHoldersEligible}. */
    public static boolean makesStaffAuthority(String roleName, Set<String> previous, Set<String> granted,
                                              Collection<String> added) {
        boolean addsPlatform = added.stream().anyMatch(code ->
                com.innbucks.userservice.security.PermissionCatalog.WILDCARD.equals(code)
                        || com.innbucks.userservice.security.PermissionCatalog.scopeOf(code)
                        == com.innbucks.userservice.security.PermissionCatalog.Scope.PLATFORM);
        boolean becomesStaff = !StaffRoles.isStaffRole(roleName, previous) && StaffRoles.isStaffRole(roleName, granted);
        return addsPlatform || becomesStaff;
    }
}
