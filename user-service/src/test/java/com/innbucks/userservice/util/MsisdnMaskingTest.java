package com.innbucks.userservice.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the MSISDN masking contract — the function is duplicated across four
 * services (no shared util module) and any drift in behaviour silently
 * un-masks PII in one service's logs while keeping it masked in another.
 * Identical to {@code innbucks.paymentservice.util.MsisdnMasking}; if you
 * change one, change all four.
 */
class MsisdnMaskingTest {

    @Test
    void mask_typicalZimbabwePhone_keepsLastFour() {
        // +263782606983 is the canonical test number used throughout the
        // session — pin the masked form so log greps stay stable.
        assertThat(MsisdnMasking.mask("+263782606983")).isEqualTo("****6983");
    }

    @Test
    void mask_keniaPhone_keepsLastFour() {
        assertThat(MsisdnMasking.mask("+254712345678")).isEqualTo("****5678");
    }

    @Test
    void mask_nullPhone_returnsBareStars() {
        // Defensive: many call sites pass a value sourced from JWT claims /
        // request bodies that can legitimately be null in odd edge cases
        // (e.g. unauthenticated probe of an auth-required path). The mask
        // must NOT NPE — a bare "****" is the safe fallback.
        assertThat(MsisdnMasking.mask(null)).isEqualTo("****");
    }

    @Test
    void mask_emptyString_returnsBareStars() {
        assertThat(MsisdnMasking.mask("")).isEqualTo("****");
    }

    @Test
    void mask_phoneShorterThanFourChars_returnsBareStars() {
        // A 1-3 char value (corrupt input, partial entry) MUST NOT expose
        // any characters — last-4-of-1 would leak the whole value.
        assertThat(MsisdnMasking.mask("123")).isEqualTo("****");
        assertThat(MsisdnMasking.mask("1")).isEqualTo("****");
    }

    @Test
    void mask_phoneExactlyFourChars_returnsBareStars() {
        // The <=4 guard treats 4-char strings as "too short to mask
        // safely" — masking would yield "****1234" which IS the full
        // value. Bare stars is the only safe choice.
        assertThat(MsisdnMasking.mask("1234")).isEqualTo("****");
    }
}
