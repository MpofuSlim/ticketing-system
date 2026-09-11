package com.innbucks.userservice.security;

import com.innbucks.userservice.entity.User;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the (role, channel) matrix that drives the MFA gate. Channel rule:
 * USSD/WhatsApp never challenge; web/mobile always for system users; opt-in
 * for customers.
 */
class MfaPolicyTest {

    // The gate-operator exemption asks whether the account resolves to any
    // permission, so the policy needs a resolver. Stubbed to "grants nothing",
    // which is what V35 seeds TEAM_MEMBER with; the one test that cares
    // overrides it.
    private final PermissionResolver permissionResolver =
            org.mockito.Mockito.mock(PermissionResolver.class);

    private final MfaPolicy policy = new MfaPolicy(permissionResolver);

    @org.junit.jupiter.api.BeforeEach
    void rolesGrantNothingByDefault() {
        org.mockito.Mockito.when(permissionResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Set.of());
    }

    private static User user(boolean mfaEnabled, User.Role... roles) {
        return User.builder()
                .id(1L)
                .roles(User.roleNames(roles))
                .mfaEnabled(mfaEnabled)
                .build();
    }

    // ---- applicable ---------------------------------------------------------

    @Test
    void applicable_trueOnWebAndMobile_falseOnUssdAndWhatsapp() {
        assertThat(policy.applicable(AuthChannel.WEB)).isTrue();
        assertThat(policy.applicable(AuthChannel.MOBILE)).isTrue();
        assertThat(policy.applicable(AuthChannel.USSD)).isFalse();
        assertThat(policy.applicable(AuthChannel.WHATSAPP)).isFalse();
    }

    // ---- required: system users on web/mobile ------------------------------

    @Test
    void required_trueForEverySystemRoleOnWebAndMobile() {
        // Derived from the enum rather than a hand-listed set: MfaPolicy defines
        // a system user as "holds any role that isn't CUSTOMER", so every role
        // added later — PRODUCT_OFFICER and PRODUCT_MANAGER included — must be
        // covered here automatically. A hardcoded list silently stops testing
        // new roles the day they're added.
        //
        // TEAM_MEMBER is excluded because it is the ONE deliberate carve-out:
        // gate staff alone on an account take the CUSTOMER opt-in path (see
        // MfaPolicy's class javadoc, and the gate-operator block below, which
        // pins that carve-out from both directions). Excluding it here rather
        // than dropping the enum-derived loop keeps the "new roles are tested
        // the day they land" property intact for every role but this one.
        for (User.Role role : java.util.Arrays.stream(User.Role.values())
                .filter(r -> r != User.Role.CUSTOMER)
                .filter(r -> r != User.Role.TEAM_MEMBER).toList()) {
            User u = user(false, role);
            assertThat(policy.required(u, AuthChannel.WEB)).as("WEB required for %s", role).isTrue();
            assertThat(policy.required(u, AuthChannel.MOBILE)).as("MOBILE required for %s", role).isTrue();
        }
    }

    @Test
    void required_falseForCustomers_evenWithMfaEnabled() {
        // Customers are opt-in — required() is the "must" gate, not the "may" gate.
        assertThat(policy.required(user(true, User.Role.CUSTOMER), AuthChannel.WEB)).isFalse();
        assertThat(policy.required(user(false, User.Role.CUSTOMER), AuthChannel.MOBILE)).isFalse();
    }

    @Test
    void required_falseOnUssdAndWhatsapp_forEverybody() {
        assertThat(policy.required(user(true, User.Role.SUPER_ADMIN), AuthChannel.USSD)).isFalse();
        assertThat(policy.required(user(true, User.Role.SUPER_ADMIN), AuthChannel.WHATSAPP)).isFalse();
        assertThat(policy.required(user(true, User.Role.CUSTOMER), AuthChannel.USSD)).isFalse();
        assertThat(policy.required(user(true, User.Role.CUSTOMER), AuthChannel.WHATSAPP)).isFalse();
    }

    // ---- shouldChallenge: system user always, customer iff opted in ---------

    @Test
    void shouldChallenge_systemUser_alwaysOnWebMobile() {
        assertThat(policy.shouldChallenge(user(false, User.Role.SUPER_ADMIN), AuthChannel.WEB)).isTrue();
        assertThat(policy.shouldChallenge(user(true, User.Role.EVENT_ORGANIZER), AuthChannel.MOBILE)).isTrue();
    }

    @Test
    void shouldChallenge_customer_onlyWhenEnabled() {
        assertThat(policy.shouldChallenge(user(true, User.Role.CUSTOMER), AuthChannel.WEB)).isTrue();
        assertThat(policy.shouldChallenge(user(false, User.Role.CUSTOMER), AuthChannel.WEB)).isFalse();
    }

    @Test
    void shouldChallenge_falseOnUssdAndWhatsapp_regardless() {
        assertThat(policy.shouldChallenge(user(true, User.Role.SUPER_ADMIN), AuthChannel.USSD)).isFalse();
        assertThat(policy.shouldChallenge(user(true, User.Role.CUSTOMER), AuthChannel.WHATSAPP)).isFalse();
    }

    // ---- the gate-operator exemption ---------------------------------------
    //
    // A pure TEAM_MEMBER (event gate staff scanning tickets) is not forced
    // through 2FA. The cases below pin the carve-out AND its blast radius: the
    // exemption must apply to exactly one role set and nothing else, because
    // MfaPolicy reads "system user = holds any non-CUSTOMER role" and a
    // containment-keyed exemption would invert that into a fleet-wide opt-out.

    @Test
    void required_falseForPureTeamMember_soTheyAreNeverForcedToEnrol() {
        User u = user(false, User.Role.TEAM_MEMBER);
        assertThat(policy.required(u, AuthChannel.WEB)).isFalse();
        assertThat(policy.required(u, AuthChannel.MOBILE)).isFalse();
    }

    @Test
    void shouldChallenge_falseForPureTeamMember_whenNotEnrolled() {
        // The point of the feature: scanner signs in with a password only.
        assertThat(policy.shouldChallenge(user(false, User.Role.TEAM_MEMBER), AuthChannel.WEB)).isFalse();
        assertThat(policy.shouldChallenge(user(false, User.Role.TEAM_MEMBER), AuthChannel.MOBILE)).isFalse();
    }

    @Test
    void shouldChallenge_falseForPureTeamMember_evenWhenAlreadyEnrolled() {
        // The enrolment state is ignored entirely for gate staff, and that is
        // the point rather than an oversight. TEAM_MEMBER used to be a system
        // user, so every team member was force-enrolled on first login and
        // still carries mfaEnabled=true. Honouring the flag would mean the
        // exemption applied only to accounts created after it shipped, leaving
        // every existing gate staffer challenged forever — observed on staging,
        // where a genuine single-role TEAM_MEMBER kept getting mfaRequired
        // because they had enrolled under the old rule.
        assertThat(policy.shouldChallenge(user(true, User.Role.TEAM_MEMBER), AuthChannel.WEB)).isFalse();
        assertThat(policy.shouldChallenge(user(true, User.Role.TEAM_MEMBER), AuthChannel.MOBILE)).isFalse();
    }

    @Test
    void enrolledTeamMemberWhoGainsASecondRole_isChallengedAgain() {
        // The secret is ignored, not cleared, so protection comes back the
        // moment the account stops being pure gate staff. Pins that the
        // widened bypass did not also widen who it applies to.
        User u = user(true, User.Role.TEAM_MEMBER, User.Role.EVENT_ORGANIZER);
        assertThat(policy.shouldChallenge(u, AuthChannel.WEB)).isTrue();
    }

    @Test
    void teamMemberHoldingAnySecondBuiltInRole_isStillFullyChallenged() {
        // THE escalation guard. {TEAM_MEMBER, X} must behave as staff for every
        // other built-in X — enum-derived so a role added later is covered the
        // day it lands. Two real paths reach such a set without compromising
        // anything: ServiceRequestService.approve adds a bundle role in place
        // (and POST /users/me/service-requests has no @PreAuthorize), and
        // DataInitializer MERGES SUPER_ADMIN onto whatever roles already sit on
        // the bootstrap-admin email. If this test ever goes green-by-weakening,
        // a scanner credential has become an MFA-free privileged session.
        for (User.Role second : java.util.Arrays.stream(User.Role.values())
                .filter(r -> r != User.Role.TEAM_MEMBER).toList()) {
            User u = user(false, User.Role.TEAM_MEMBER, second);
            assertThat(policy.gateOperatorExempt(u))
                    .as("exempt must be false for {TEAM_MEMBER, %s}", second).isFalse();
            assertThat(policy.shouldChallenge(u, AuthChannel.WEB))
                    .as("WEB challenge for {TEAM_MEMBER, %s}", second).isTrue();
            assertThat(policy.shouldChallenge(u, AuthChannel.MOBILE))
                    .as("MOBILE challenge for {TEAM_MEMBER, %s}", second).isTrue();
        }
    }

    @Test
    void teamMemberPlusCustomer_isStillChallenged() {
        // Called out separately from the loop above because it is the one pair
        // where BOTH halves are individually non-staff, so a reader might
        // expect it to stay exempt. It does not: the exemption is exact set
        // equality, and anything we did not explicitly exempt fails closed.
        User u = user(false, User.Role.TEAM_MEMBER, User.Role.CUSTOMER);
        assertThat(policy.gateOperatorExempt(u)).isFalse();
        assertThat(policy.shouldChallenge(u, AuthChannel.WEB)).isTrue();
    }

    @Test
    void teamMemberPlusOperatorCreatedRole_isStillChallenged() {
        // Roles are free-text rows since V35, so a set can hold a name that is
        // not in the enum at all. Built via the raw setter because
        // User.roleNames() only takes enum constants.
        User u = User.builder()
                .id(1L)
                .roles(java.util.Set.of(User.Role.TEAM_MEMBER.name(), "GATE_SUPERVISOR"))
                .mfaEnabled(false)
                .build();
        assertThat(policy.gateOperatorExempt(u)).isFalse();
        assertThat(policy.required(u, AuthChannel.WEB)).isTrue();
        assertThat(policy.shouldChallenge(u, AuthChannel.WEB)).isTrue();
    }

    @Test
    void gateOperatorExempt_trueOnlyForExactlyTeamMember() {
        assertThat(policy.gateOperatorExempt(user(false, User.Role.TEAM_MEMBER))).isTrue();
        assertThat(policy.gateOperatorExempt(user(false, User.Role.CUSTOMER))).isFalse();
        assertThat(policy.gateOperatorExempt(user(false, User.Role.SUPER_ADMIN))).isFalse();
        // Roleless and null-roles accounts are not gate operators either.
        assertThat(policy.gateOperatorExempt(User.builder().id(1L).roles(java.util.Set.of()).build()))
                .isFalse();
        assertThat(policy.gateOperatorExempt(User.builder().id(1L).build())).isFalse();
    }

    @Test
    void pureTeamMemberHoldingAnyPermission_losesTheExemption() {
        // THE second escalation guard, and the one the role set cannot express.
        // PUT /admin/roles/{name}/permissions is deliberately allowed on
        // built-in roles, so an operator with roles:write can grant TEAM_MEMBER
        // a real permission. The role set is still exactly {TEAM_MEMBER}, so
        // set equality alone would keep exempting an account that now has
        // authority. Privilege, not the name, is what must gate the exemption.
        org.mockito.Mockito.when(permissionResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Set.of(PermissionCatalog.USERS_ROLES_WRITE));

        User u = user(false, User.Role.TEAM_MEMBER);
        assertThat(policy.gateOperatorExempt(u)).isFalse();
        assertThat(policy.required(u, AuthChannel.WEB)).isTrue();
        assertThat(policy.shouldChallenge(u, AuthChannel.WEB)).isTrue();
    }

    @Test
    void permissionsAreOnlyResolvedForCandidateGateOperators() {
        // The resolve() is a DB read, so it must not land on every login. Only
        // an account that already passed the single-role test should reach it.
        policy.shouldChallenge(user(false, User.Role.SUPER_ADMIN), AuthChannel.WEB);
        policy.shouldChallenge(user(false, User.Role.CUSTOMER), AuthChannel.WEB);
        policy.shouldChallenge(user(false, User.Role.TEAM_MEMBER, User.Role.CUSTOMER), AuthChannel.WEB);
        org.mockito.Mockito.verify(permissionResolver, org.mockito.Mockito.never())
                .resolve(org.mockito.ArgumentMatchers.any());

        policy.shouldChallenge(user(false, User.Role.TEAM_MEMBER), AuthChannel.WEB);
        org.mockito.Mockito.verify(permissionResolver, org.mockito.Mockito.times(1))
                .resolve(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void gateOperatorExemption_doesNotReachUssdOrWhatsapp() {
        // applicable() already short-circuits both channels; asserted so the
        // exemption can't be read as introducing a new channel behaviour.
        User u = user(true, User.Role.TEAM_MEMBER);
        assertThat(policy.shouldChallenge(u, AuthChannel.USSD)).isFalse();
        assertThat(policy.shouldChallenge(u, AuthChannel.WHATSAPP)).isFalse();
    }

    // ---- header parsing -----------------------------------------------------

    @Test
    void parseHeader_blank_defaultsToWeb() {
        assertThat(AuthChannel.parseOrDefault(null)).isEqualTo(AuthChannel.WEB);
        assertThat(AuthChannel.parseOrDefault("")).isEqualTo(AuthChannel.WEB);
        assertThat(AuthChannel.parseOrDefault("   ")).isEqualTo(AuthChannel.WEB);
    }

    @Test
    void parseHeader_isCaseInsensitiveAndTrimmed() {
        assertThat(AuthChannel.parseOrDefault(" mobile ")).isEqualTo(AuthChannel.MOBILE);
        assertThat(AuthChannel.parseOrDefault("ussd")).isEqualTo(AuthChannel.USSD);
        assertThat(AuthChannel.parseOrDefault("WHATSAPP")).isEqualTo(AuthChannel.WHATSAPP);
    }

    @Test
    void parseHeader_unknown_defaultsToWeb_safeByDefault() {
        // An unknown / typo value falls back to WEB — strictest-by-default.
        assertThat(AuthChannel.parseOrDefault("desktop")).isEqualTo(AuthChannel.WEB);
    }

    // ---- forPublicLogin: the untrusted-edge clamp (MFA-bypass regression) ----

    @Test
    void forPublicLogin_honoursOnlyAppChannels() {
        assertThat(AuthChannel.forPublicLogin("WEB")).isEqualTo(AuthChannel.WEB);
        assertThat(AuthChannel.forPublicLogin(" mobile ")).isEqualTo(AuthChannel.MOBILE);
    }

    @Test
    void forPublicLogin_collapsesMfaFreeChannelsToWeb() {
        // The whole point: a public caller must NOT be able to assert USSD /
        // WhatsApp (which MfaPolicy treats as second-factor-free) to skip MFA.
        assertThat(AuthChannel.forPublicLogin("USSD")).isEqualTo(AuthChannel.WEB);
        assertThat(AuthChannel.forPublicLogin("ussd")).isEqualTo(AuthChannel.WEB);
        assertThat(AuthChannel.forPublicLogin("WHATSAPP")).isEqualTo(AuthChannel.WEB);
    }

    @Test
    void forPublicLogin_blankAndUnknown_defaultToWeb() {
        assertThat(AuthChannel.forPublicLogin(null)).isEqualTo(AuthChannel.WEB);
        assertThat(AuthChannel.forPublicLogin("")).isEqualTo(AuthChannel.WEB);
        assertThat(AuthChannel.forPublicLogin("desktop")).isEqualTo(AuthChannel.WEB);
    }

    @Test
    void forPublicLogin_systemUserOnUssdHeader_stillChallenged() {
        // End-to-end intent: even with X-Auth-Channel: USSD, a system user's
        // login (parsed via the public clamp) still trips the MFA gate.
        AuthChannel clamped = AuthChannel.forPublicLogin("USSD");
        assertThat(policy.shouldChallenge(user(false, User.Role.SUPER_ADMIN), clamped)).isTrue();
    }
}
