package com.innbucks.userservice.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StaffEmailPolicy#evaluate} is the one written statement of which
 * addresses may hold staff authority (V44). Every step of the algorithm is
 * pinned here: strip, printable-ASCII only (never NFKC/punycode), split at the
 * LAST {@code @}, Locale.ROOT, exact domain match, local-part rules, denylist.
 */
class StaffEmailPolicyTest {

    private static final Set<String> DOMAINS = Set.of("innbucks.co.zw", "innbucks.co.ke");
    private static final Set<String> DENIED = Set.of("admin", "support", "info", "noreply", "staff", "finance");

    private static StaffEmailPolicy.Result eval(String raw) {
        return StaffEmailPolicy.evaluate(raw, DOMAINS, DENIED);
    }

    @ParameterizedTest
    @CsvSource({
            "tariro.moyo@innbucks.co.zw, tariro.moyo@innbucks.co.zw",
            "'  Tariro.Moyo@INNBUCKS.CO.ZW  ', tariro.moyo@innbucks.co.zw",
            "t_moyo-2@innbucks.co.ke, t_moyo-2@innbucks.co.ke",
            "a@innbucks.co.zw, a@innbucks.co.zw",
            "a1@innbucks.co.zw, a1@innbucks.co.zw"
    })
    @DisplayName("an own address on a staff domain is accepted and lower-cased")
    void accepted(String raw, String stored) {
        StaffEmailPolicy.Result r = eval(raw);
        assertThat(r.ok()).isTrue();
        assertThat(r.normalized()).isEqualTo(stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "tariro@gmail.com",
            "tariro@mail.innbucks.co.zw",          // subdomain
            "tariro@innbucks.co.zw.",              // trailing-dot FQDN
            "tariro@xinnbucks.co.zw",              // prefix variation
            "tariro@innbucks.co.zw.evil.com",      // suffix variation
            "tariro@xn--innbucks-9za.co.zw",       // punycode label
            "\"x@innbucks.co.zw\"@evil.com",       // quoted local part: judged by the last @
            "a@innbucks.co.zw@evil.com",           // two @: judged by the last @
            "tariro@innbucks.co.zw​",         // invisible character in the domain
            "tariro@іnnbucks.co.zw",               // Cyrillic i look-alike
            "tariro@"
    })
    @DisplayName("anything but an exact staff domain is email_domain_not_allowed")
    void domainRefused(String raw) {
        StaffEmailPolicy.Result r = eval(raw);
        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(StaffEmailPolicy.EMAIL_DOMAIN_NOT_ALLOWED);
    }

    @ParameterizedTest
    @CsvSource({
            "tariro+ops@innbucks.co.zw, plus_tag",
            "tarirö@innbucks.co.zw, non_ascii",
            "a@b@innbucks.co.zw, local_part",
            ".tariro@innbucks.co.zw, local_part",
            "tariro.@innbucks.co.zw, local_part",
            "tari..ro@innbucks.co.zw, local_part",
            "'tariro moyo@innbucks.co.zw', non_ascii",
            "support@innbucks.co.zw, shared_mailbox",
            "SUPPORT@innbucks.co.zw, shared_mailbox",
            "noreply@innbucks.co.ke, shared_mailbox"
    })
    @DisplayName("the local part: no +tag, the pattern, no shared mailbox")
    void localPartRefused(String raw, String reason) {
        StaffEmailPolicy.Result r = eval(raw);
        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(StaffEmailPolicy.EMAIL_NOT_ACCEPTED);
        assertThat(r.reason()).isEqualTo(reason);
    }

    @Test
    @DisplayName("support@innbucks.co.zw is refused as a shared mailbox")
    void supportMailboxRefused() {
        assertThat(eval("support@innbucks.co.zw").reason()).isEqualTo("shared_mailbox");
    }

    @Test
    @DisplayName("an upper-case domain still matches under the tr-TR default locale (Locale.ROOT, never the default)")
    void turkishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            // Under tr-TR, "I".toLowerCase() is a dotless ı — a default-locale
            // lower-casing would turn INNBUCKS into ınnbucks and refuse it.
            StaffEmailPolicy.Result r = eval("TARIRO.MOYO@INNBUCKS.CO.ZW");
            assertThat(r.ok()).isTrue();
            assertThat(r.normalized()).isEqualTo("tariro.moyo@innbucks.co.zw");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("the local part is at most 64 characters")
    void localPartLength() {
        assertThat(eval("a".repeat(64) + "@innbucks.co.zw").ok()).isTrue();
        assertThat(eval("a".repeat(65) + "@innbucks.co.zw").reason()).isEqualTo("local_part");
    }

    @Test
    @DisplayName("isReserved: a staff domain or any subdomain, any case, trailing dots ignored — for NON-staff writers")
    void reserved() {
        assertThat(StaffEmailPolicy.isReserved("x@innbucks.co.zw", DOMAINS)).isTrue();
        assertThat(StaffEmailPolicy.isReserved("X@INNBUCKS.CO.KE", DOMAINS)).isTrue();
        assertThat(StaffEmailPolicy.isReserved("x@mail.innbucks.co.zw", DOMAINS)).isTrue();
        assertThat(StaffEmailPolicy.isReserved("x@innbucks.co.zw.", DOMAINS)).isTrue();
        assertThat(StaffEmailPolicy.isReserved("x+tag@innbucks.co.zw", DOMAINS)).isTrue();
        assertThat(StaffEmailPolicy.isReserved("x@notinnbucks.co.zw", DOMAINS)).isFalse();
        assertThat(StaffEmailPolicy.isReserved("x@gmail.com", DOMAINS)).isFalse();
        assertThat(StaffEmailPolicy.isReserved(null, DOMAINS)).isFalse();
        // A cell naming no staff domain reserves nothing.
        assertThat(StaffEmailPolicy.isReserved("x@innbucks.co.zw", Set.of())).isFalse();
    }

    @Test
    @DisplayName("the domain message names the configured domains")
    void message() {
        assertThat(StaffEmailPolicy.domainNotAllowedMessage(List.of("innbucks.co.zw", "innbucks.co.ke")))
                .isEqualTo("Staff accounts must use an InnBucks email address ending in @innbucks.co.zw or "
                        + "@innbucks.co.ke.");
        assertThat(StaffEmailPolicy.domainNotAllowedMessage(List.of("a.com", "b.com", "c.com")))
                .isEqualTo("Staff accounts must use an InnBucks email address ending in @a.com, @b.com or @c.com.");
    }
}
