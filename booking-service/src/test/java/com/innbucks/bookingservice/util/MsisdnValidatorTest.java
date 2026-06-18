package com.innbucks.bookingservice.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the booking phone-number guard. The motivating bug: an 11-digit
 * Zimbabwe number ({@code +26378260983}, a digit short of the valid
 * {@code +263782606983}) was accepted, stored, and only failed at Twilio with
 * error 63024 — a confirmed booking whose e-ticket never arrived. These tests
 * lock in that such numbers are rejected at creation, and that valid ones are
 * canonicalised to E.164.
 */
class MsisdnValidatorTest {

    @Test
    void zimbabweNumberMissingADigit_isRejected() {
        // 11 digits; a valid ZW mobile is 12 (+263 + 9). This is the exact
        // shape that reached Twilio and bounced with 63024.
        assertThat(MsisdnValidator.normalizeToE164("+26378260983", "ZW")).isEmpty();
        assertThat(MsisdnValidator.isValid("+26378260983", "ZW")).isFalse();
    }

    @Test
    void validZimbabweNumber_isAcceptedAndCanonicalised() {
        assertThat(MsisdnValidator.normalizeToE164("+263782606983", "ZW"))
                .contains("+263782606983");
    }

    @Test
    void localFormWithoutPlus_usesDefaultRegion_andGainsE164Plus() {
        // A ZW customer typing the national form on the ZW cell.
        assertThat(MsisdnValidator.normalizeToE164("0782606983", "ZW"))
                .contains("+263782606983");
    }

    @Test
    void barePluslessInternational_isParsedAgainstDefaultRegion() {
        // "263782606983" with no '+', default region ZW -> still resolves.
        assertThat(MsisdnValidator.normalizeToE164("263782606983", "ZW"))
                .contains("+263782606983");
    }

    @Test
    void spacesAndDashes_areTolerated() {
        assertThat(MsisdnValidator.normalizeToE164("+263 78 260 6983", "ZW"))
                .contains("+263782606983");
    }

    @Test
    void foreignNumberWithPlus_validatesByItsOwnCountryCode_notDefaultRegion() {
        // A Kenyan number presented to a ZW cell: the +254 wins over default ZW.
        assertThat(MsisdnValidator.normalizeToE164("+254712345678", "ZW"))
                .contains("+254712345678");
    }

    @Test
    void blankOrNull_isRejected() {
        assertThat(MsisdnValidator.normalizeToE164(null, "ZW")).isEmpty();
        assertThat(MsisdnValidator.normalizeToE164("", "ZW")).isEmpty();
        assertThat(MsisdnValidator.normalizeToE164("   ", "ZW")).isEmpty();
    }

    @Test
    void nonNumericGarbage_isRejected() {
        assertThat(MsisdnValidator.normalizeToE164("not-a-phone", "ZW")).isEmpty();
        assertThat(MsisdnValidator.normalizeToE164("+1", "ZW")).isEmpty();
    }

    @Test
    void wayTooLong_isRejected() {
        assertThat(MsisdnValidator.normalizeToE164("+2637826069830000000", "ZW")).isEmpty();
    }
}
