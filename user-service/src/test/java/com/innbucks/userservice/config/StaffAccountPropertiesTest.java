package com.innbucks.userservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boot validation of {@code staff.*} (V44): a value that would widen who can be
 * staff, or break the invite link, stops the service.
 */
class StaffAccountPropertiesTest {

    private static StaffAccountProperties with(List<String> domains, String consoleUrl) {
        StaffAccountProperties p = new StaffAccountProperties();
        p.setAllowedEmailDomains(domains);
        p.setConsoleBaseUrl(consoleUrl);
        return p;
    }

    @Test
    @DisplayName("domains are lower-cased with Locale.ROOT and kept in order")
    void normalised() {
        StaffAccountProperties p = with(List.of(" InnBucks.CO.ZW ", "innbucks.co.ke"), "https://foundry.innbucks.co.zw/");
        p.afterPropertiesSet();
        assertThat(p.domains()).containsExactly("innbucks.co.zw", "innbucks.co.ke");
        assertThat(p.domainsConfigured()).isTrue();
        assertThat(p.consoleBaseUrl()).isEqualTo("https://foundry.innbucks.co.zw");
        assertThat(p.getInviteTtl()).isEqualTo(Duration.ofHours(72));
        assertThat(p.getInvitePath()).isEqualTo("/set-password");
        assertThat(p.enforcing()).isFalse();
        assertThat(p.deniedLocalPartSet()).contains("support", "admin", "noreply", "staff");
    }

    @ParameterizedTest
    @ValueSource(strings = {"*.innbucks.co.zw", "@innbucks.co.zw", ".innbucks.co.zw", "innbucks.co.zw.",
            "innbücks.co.zw", "innbucks", "-innbucks.co.zw", "innbucks..co.zw", "inn bucks.co.zw"})
    @DisplayName("a wildcard, leading @ or dot, trailing dot, non-ASCII or malformed domain FAILS boot")
    void badDomainFailsBoot(String domain) {
        assertThatThrownBy(() -> with(List.of(domain), "").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STAFF_ALLOWED_EMAIL_DOMAINS");
    }

    @Test
    @DisplayName("a blank domain list boots — every staff grant then answers 503")
    void blankBoots() {
        StaffAccountProperties p = with(List.of(), "");
        p.afterPropertiesSet();
        assertThat(p.domainsConfigured()).isFalse();
        assertThat(p.consoleBaseUrl()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://foundry.innbucks.co.zw", "foundry.innbucks.co.zw",
            "https://foundry.innbucks.co.zw/console", "https://foundry.innbucks.co.zw?x=1",
            "https://foundry.innbucks.co.zw#frag", "https://user@foundry.innbucks.co.zw"})
    @DisplayName("the console URL must be an https ORIGIN")
    void badConsoleUrl(String url) {
        assertThatThrownBy(() -> with(List.of("innbucks.co.zw"), url).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STAFF_CONSOLE_BASE_URL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"set-password", "/set#password", "/set?x", "/set password"})
    @DisplayName("the invite path starts with / and carries no ? # or space")
    void badInvitePath(String path) {
        StaffAccountProperties p = with(List.of("innbucks.co.zw"), "https://foundry.innbucks.co.zw");
        p.setInvitePath(path);
        assertThatThrownBy(p::afterPropertiesSet).hasMessageContaining("STAFF_INVITE_PATH");
    }

    @Test
    @DisplayName("TTL and both quotas must be positive")
    void positives() {
        StaffAccountProperties ttl = with(List.of(), "");
        ttl.setInviteTtl(Duration.ZERO);
        assertThatThrownBy(ttl::afterPropertiesSet).hasMessageContaining("STAFF_INVITE_TTL");
        StaffAccountProperties resend = with(List.of(), "");
        resend.setInviteResendLimit(0);
        assertThatThrownBy(resend::afterPropertiesSet).hasMessageContaining("STAFF_INVITE_RESEND_LIMIT");
        StaffAccountProperties create = with(List.of(), "");
        create.setCreateDailyLimit(0);
        assertThatThrownBy(create::afterPropertiesSet).hasMessageContaining("STAFF_CREATE_DAILY_LIMIT");
    }
}
