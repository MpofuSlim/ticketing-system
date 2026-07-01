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
                .roles(EnumSet.copyOf(java.util.Arrays.asList(roles)))
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
        for (User.Role role : new User.Role[]{
                User.Role.SUPER_ADMIN, User.Role.EVENT_ORGANIZER, User.Role.TEAM_MEMBER,
                User.Role.MERCHANT_ADMIN, User.Role.SHOP_ADMIN, User.Role.SHOP_USER}) {
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
}
