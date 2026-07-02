package com.innbucks.userservice.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins user-service's phone canonicalisation. The motivating bug: the System
 * Users console showed one staff member as {@code +263777224008} and another as
 * the bare {@code 782606983} because user-service stored whatever the caller
 * typed. These tests lock in that every accepted spelling of a number collapses
 * to one E.164 form (so storage, the {@code uk_users_phone_country} uniqueness
 * guard, and login-by-phone all agree) and that malformed input is rejected.
 */
class MsisdnValidatorTest {

    @Test
    void bareSubscriberNumber_gainsCountryCodeFromDefaultRegion() {
        // The exact "782606983" shape from the System Users screen — 9 digits,
        // no country code, no trunk 0 — becomes the canonical +263 form.
        assertThat(MsisdnValidator.normalizeToE164("782606983", "ZW"))
                .contains("+263782606983");
    }

    @Test
    void localTrunkZeroForm_isCanonicalised() {
        assertThat(MsisdnValidator.normalizeToE164("0782606983", "ZW"))
                .contains("+263782606983");
    }

    @Test
    void alreadyE164_isReturnedUnchanged_idempotent() {
        // Idempotency is what makes it safe to normalise at every layer.
        String once = MsisdnValidator.normalizeToE164("+263777224008", "ZW").orElseThrow();
        assertThat(once).isEqualTo("+263777224008");
        assertThat(MsisdnValidator.normalizeToE164(once, "ZW")).contains("+263777224008");
    }

    @Test
    void spacesAndDashes_areTolerated() {
        assertThat(MsisdnValidator.normalizeToE164("+263 77 722 4008", "ZW"))
                .contains("+263777224008");
    }

    @Test
    void kenyaCellDefaultRegion_appliesKenyaCountryCode() {
        // Same bare subscriber shape on the KE cell resolves to +254, proving
        // the canonical country code follows the deployment's cell country.
        assertThat(MsisdnValidator.normalizeToE164("712345678", "KE"))
                .contains("+254712345678");
    }

    @Test
    void foreignNumberWithPlus_keepsItsOwnCountryCode_notDefaultRegion() {
        assertThat(MsisdnValidator.normalizeToE164("+254712345678", "ZW"))
                .contains("+254712345678");
    }

    @Test
    void zimbabweNumberMissingADigit_isRejected() {
        assertThat(MsisdnValidator.normalizeToE164("+26378260983", "ZW")).isEmpty();
        assertThat(MsisdnValidator.isValid("+26378260983", "ZW")).isFalse();
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
}
