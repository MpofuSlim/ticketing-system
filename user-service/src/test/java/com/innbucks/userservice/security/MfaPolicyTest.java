package com.innbucks.userservice.security;

import com.innbucks.userservice.entity.User;
import org.junit.jupiter.api.Test;

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

    @Test
    void required_trueForEverySystemRoleOnWebAndMobile() {
        // Derived from the enum rather than a hand-listed set: MfaPolicy defines
        // a system user as "holds any role that isn't CUSTOMER", so every role
        // added later — PRODUCT_OFFICER and PRODUCT_MANAGER included — must be
        // covered here automatically. A hardcoded list silently stops testing
        // new roles the day they're added. There is no exception: the one
        // carve-out (TEAM_MEMBER gate operators) was retired by V55.
        for (User.Role role : java.util.Arrays.stream(User.Role.values())
                .filter(r -> r != User.Role.CUSTOMER).toList()) {
            User u = user(false, role);
            assertThat(policy.required(u, AuthChannel.WEB)).as("WEB required for %s", role).isTrue();
            assertThat(policy.required(u, AuthChannel.MOBILE)).as("MOBILE required for %s", role).isTrue();
        }
    }

    /**
     * A sales agent (V56) holds CUSTOMER + SALES_AGENT. SALES_AGENT grants no
     * permission; it exists so the Foundry portal sign-in is a system user's and
     * forces 2FA. The app's phone-proof session never reaches this policy (it is
     * minted CUSTOMER-only; PhoneProofScopeTest).
     */
    @Test
    void required_forASalesAgent_evenThoughTheyAreACustomerToo() {
        User agent = user(false, User.Role.CUSTOMER, User.Role.SALES_AGENT);
        assertThat(policy.required(agent, AuthChannel.WEB)).isTrue();
        assertThat(policy.shouldChallenge(agent, AuthChannel.WEB)).isTrue();
        assertThat(policy.required(agent, AuthChannel.USSD)).isFalse();
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

    // ---- no exemption for anyone (V55) -------------------------------------
    //
    // Until V55 an account holding exactly TEAM_MEMBER (gate staff) was never
    // challenged. Team members were retired: gate staff are super-app scanners
    // with no account here. A stray TEAM_MEMBER string (a restored row, a
    // direct SQL grant) must now be an ordinary staff role, never a way out of
    // the second factor.

    @Test
    void aLeftoverTeamMemberRole_isAnOrdinarySystemRole() {
        User u = User.builder()
                .id(1L)
                .roles(java.util.Set.of("TEAM_MEMBER"))
                .mfaEnabled(false)
                .build();
        assertThat(policy.required(u, AuthChannel.WEB)).isTrue();
        assertThat(policy.required(u, AuthChannel.MOBILE)).isTrue();
        assertThat(policy.shouldChallenge(u, AuthChannel.WEB)).isTrue();
        assertThat(policy.shouldChallenge(u, AuthChannel.MOBILE)).isTrue();
    }

    @Test
    void anOperatorCreatedRole_isChallenged() {
        // Roles are free-text rows since V35, so a set can hold a name that is
        // not in the enum at all. Built via the raw setter because
        // User.roleNames() only takes enum constants.
        User u = User.builder()
                .id(1L)
                .roles(java.util.Set.of("GATE_SUPERVISOR"))
                .mfaEnabled(false)
                .build();
        assertThat(policy.required(u, AuthChannel.WEB)).isTrue();
        assertThat(policy.shouldChallenge(u, AuthChannel.WEB)).isTrue();
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
