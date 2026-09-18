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

    private final MfaPolicy policy = new MfaPolicy();

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

    /**
     * The two roles MfaPolicy exempts from FORCED 2FA. Kept as the test's own
     * literal rather than reaching into the policy's private set, so that
     * widening the exemption in production code fails here and has to be
     * argued for, instead of the test silently agreeing with itself.
     */
    private static final EnumSet<User.Role> EXEMPT =
            EnumSet.of(User.Role.CUSTOMER, User.Role.TEAM_MEMBER);

    @Test
    void required_trueForEverySystemRoleOnWebAndMobile() {
        // Derived from the enum rather than a hand-listed set: MfaPolicy forces
        // 2FA on every role that isn't explicitly exempt, so a role added later
        // must be covered here automatically and defaults to REQUIRED. A
        // hardcoded list silently stops testing new roles the day they're added.
        for (User.Role role : java.util.Arrays.stream(User.Role.values())
                .filter(r -> !EXEMPT.contains(r)).toList()) {
            User u = user(false, role);
            assertThat(policy.required(u, AuthChannel.WEB)).as("WEB required for %s", role).isTrue();
            assertThat(policy.required(u, AuthChannel.MOBILE)).as("MOBILE required for %s", role).isTrue();
        }
    }

    // ---- TEAM_MEMBER: exempt from being FORCED, still free to opt in --------

    @Test
    void required_falseForTeamMembers_theyAreGateStaffNotAdmins() {
        assertThat(policy.required(user(false, User.Role.TEAM_MEMBER), AuthChannel.WEB)).isFalse();
        assertThat(policy.required(user(false, User.Role.TEAM_MEMBER), AuthChannel.MOBILE)).isFalse();
        // Even already-enrolled: required() is the "must" gate. This is what
        // lets AuthController's disable guard release a team member who was
        // force-enrolled under the previous policy.
        assertThat(policy.required(user(true, User.Role.TEAM_MEMBER), AuthChannel.WEB)).isFalse();
    }

    @Test
    void shouldChallenge_teamMember_onlyWhenTheyOptedIn() {
        // Not forced...
        assertThat(policy.shouldChallenge(user(false, User.Role.TEAM_MEMBER), AuthChannel.WEB)).isFalse();
        assertThat(policy.shouldChallenge(user(false, User.Role.TEAM_MEMBER), AuthChannel.MOBILE)).isFalse();
        // ...but a factor they deliberately switched on is never ignored.
        assertThat(policy.shouldChallenge(user(true, User.Role.TEAM_MEMBER), AuthChannel.WEB)).isTrue();
    }

    @Test
    void teamMemberWhoIsAlsoACustomer_isStillExempt() {
        // Both roles are exempt, so the user is exempt — the organizer's
        // gate staff who also buys tickets on the same account.
        assertThat(policy.required(user(false, User.Role.TEAM_MEMBER, User.Role.CUSTOMER),
                AuthChannel.WEB)).isFalse();
    }

    // ---- the escalation case the exemption must NOT open -------------------

    @Test
    void required_trueWhenTeamMemberIsHeldAlongsideAnyPrivilegedRole() {
        // The predicate asks "holds any NON-exempt role", not "holds any exempt
        // role". Reversed, acquiring TEAM_MEMBER would be a way to drop your own
        // 2FA — so every privileged pairing is pinned here, not just one.
        for (User.Role privileged : java.util.Arrays.stream(User.Role.values())
                .filter(r -> !EXEMPT.contains(r)).toList()) {
            User u = user(false, User.Role.TEAM_MEMBER, privileged);
            assertThat(policy.required(u, AuthChannel.WEB))
                    .as("TEAM_MEMBER + %s must still require MFA", privileged).isTrue();
            assertThat(policy.shouldChallenge(u, AuthChannel.WEB))
                    .as("TEAM_MEMBER + %s must still be challenged", privileged).isTrue();
        }
    }

    @Test
    void required_trueForAnOperatorCreatedCustomRole() {
        // Roles are DATA since V35 — an operator can create one at runtime, and
        // it will never be in the exempt set. It must therefore default to
        // REQUIRED, or "create a role" becomes a way to opt out of MFA.
        User custom = User.builder().id(1L)
                .roles(new java.util.LinkedHashSet<>(java.util.List.of("GATE_SUPERVISOR")))
                .mfaEnabled(false).build();
        assertThat(policy.required(custom, AuthChannel.WEB)).isTrue();

        // And the same custom role alongside TEAM_MEMBER is still required.
        User mixed = User.builder().id(2L)
                .roles(new java.util.LinkedHashSet<>(
                        java.util.List.of(User.Role.TEAM_MEMBER.name(), "GATE_SUPERVISOR")))
                .mfaEnabled(false).build();
        assertThat(policy.required(mixed, AuthChannel.WEB)).isTrue();
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
