package com.innbucks.common.ticket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two ticket-number shapes (see {@link TicketNumbers}): the current
 * one is recognised, the legacy one stays recognised forever, and neither
 * pattern widens into an all-digit string a card scanner would flag.
 */
class TicketNumbersTest {

    @Test
    void theAlphabetIsCrockfordBase32_upperCase_withoutILOU() {
        assertThat(TicketNumbers.ALPHABET).hasSize(32);
        assertThat(TicketNumbers.ALPHABET.chars().distinct().count()).isEqualTo(32);
        assertThat(TicketNumbers.ALPHABET).doesNotContain("I", "L", "O", "U");
        assertThat(TicketNumbers.ALPHABET).isEqualTo(TicketNumbers.ALPHABET.toUpperCase());
    }

    @Test
    void everyAlphabetSymbol_isAcceptedInEveryPosition_andNothingElseIs() {
        for (char c = '!'; c <= '~'; c++) {
            String tail = String.valueOf(c).repeat(5);
            String value = "20261013-" + tail + "-A" + tail.substring(1);
            boolean inAlphabet = TicketNumbers.ALPHABET.indexOf(c) >= 0;
            assertThat(TicketNumbers.isCurrent(value)).as("symbol %s", c).isEqualTo(inAlphabet);
        }
    }

    @Test
    void theCurrentShape_isRecognised() {
        assertThat(TicketNumbers.isCurrent("20261013-7KQ4M-9XZ2B")).isTrue();
        assertThat(TicketNumbers.isTicketNumber("20261013-7KQ4M-9XZ2B")).isTrue();
        assertThat(TicketNumbers.isLegacy("20261013-7KQ4M-9XZ2B")).isFalse();
    }

    @Test
    void theLegacyShape_staysRecognised_withAnyFinalLetter() {
        for (char letter = 'A'; letter <= 'Z'; letter++) {
            String legacy = "20260419-48291" + letter;
            assertThat(TicketNumbers.isLegacy(legacy)).as(legacy).isTrue();
            assertThat(TicketNumbers.isTicketNumber(legacy)).as(legacy).isTrue();
        }
        assertThat(TicketNumbers.isCurrent("20260419-48291X")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "20261013-12345-67890",    // all digits: never issued, reads as a card number
            "20261013-7kq4m-9xz2b",    // issued upper case only
            "20261013-7KQ4M-9XZ2O",    // O is not a symbol
            "20261013-7KQ4M9XZ2B",     // a dash missing
            "20261013-7KQ4M-9XZ2B1",   // too long
            "2026101-7KQ4M-9XZ2B",     // short date
            " 20261013-7KQ4M-9XZ2B",   // not trimmed
            "20260419-4829X",          // legacy, too short
            "20260419-48291x",         // legacy, lower case
            ""
    })
    void anythingElse_isNotATicketNumber_exactlyAsIssued(String value) {
        assertThat(TicketNumbers.isTicketNumber(value)).isFalse();
    }

    @Test
    void null_isNotATicketNumber() {
        assertThat(TicketNumbers.isTicketNumber(null)).isFalse();
        assertThat(TicketNumbers.canonical(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "20261013-7KQ4M-9XZ2B",
            "20261013-7kq4m-9xz2b",
            "  20261013-7KQ4M-9XZ2B\t",
            "20261013 7KQ4M 9XZ2B",
            "20261013 7KQ4M 9XZ2B",
            "202610137KQ4M9XZ2B",
            "20261013–7KQ4M—9XZ2B",
            "20261013−7kq4m-9xz2b"
    })
    void aTypedOrPastedCurrentNumber_canonicalisesToTheIssuedForm(String typed) {
        assertThat(TicketNumbers.canonical(typed)).contains("20261013-7KQ4M-9XZ2B");
    }

    @ParameterizedTest
    @ValueSource(strings = {"20260930-00012K", "20260930-00012k", "2026093000012K", "20260930 00012K"})
    void aTypedLegacyNumber_canonicalisesToTheIssuedForm(String typed) {
        assertThat(TicketNumbers.canonical(typed)).contains("20260930-00012K");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "20261013-12345-67890",     // all digits
            "201610131234567890",       // 18 digits: a card number, never a ticket
            "20261013-7KQ4M-9XZ2O",     // O is not a symbol, and nothing is substituted
            "20261013-7KQ4M-9XZß",   // a sharp s is not "SS"
            "INN-20261013-ABC123",
            "not a ticket"
    })
    void anythingElse_hasNoCanonicalForm(String typed) {
        assertThat(TicketNumbers.canonical(typed)).isEmpty();
    }
}
