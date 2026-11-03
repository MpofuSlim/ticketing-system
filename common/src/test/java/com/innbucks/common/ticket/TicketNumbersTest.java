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

    @Test
    void aTypedLegacyNumber_withAnyFinalLetter_canonicalisesToTheIssuedForm() {
        // canonical() spells the legacy shape out a second time, so this pins it
        // separately from isLegacy: narrowed to the Crockford set, every legacy
        // ticket ending in I, L, O or U would stop being searchable.
        for (char letter = 'A'; letter <= 'Z'; letter++) {
            String issued = "20260930-00012" + letter;
            char lower = Character.toLowerCase(letter);
            assertThat(TicketNumbers.canonical(issued)).as(issued).contains(issued);
            assertThat(TicketNumbers.canonical("20260930-00012" + lower)).as("lower %s", issued).contains(issued);
            assertThat(TicketNumbers.canonical("2026093000012" + lower)).as("dashless %s", issued).contains(issued);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "4111 1111 1111 1111 AB",   // a Visa with two letters typed after it
            "5500 0000 0000 0004 CV",   // a Mastercard, likewise
            "2221 0000 0000 0009 AB",   // Mastercard's 2-series
            "2200 0000 0000 0004 AB",   // Mir
            "41111111111111111X",       // 17 digits and a letter: the current shape's length
            "4111111111111X",           // a 13-digit card and a letter: the legacy shape's length
            "41111111-11111-111AB",     // dashed exactly like a ticket, still no date
            "20261301-7KQ4M-9XZ2B",     // month 13
            "20261032-00012K",          // day 32
            "19991013-7KQ4M-9XZ2B"      // never issued before this century
    })
    void aTypedNumberWhoseFirstEightDigitsAreNotAnIssueDate_hasNoCanonicalForm(String typed) {
        assertThat(TicketNumbers.canonical(typed)).isEmpty();
    }

    @Test
    void theDateCheck_isForTypedInputOnly_theIssuedShapesStayAsTheyWere() {
        // Exactly-as-issued recognition never tightened: the legacy shape is still
        // ^\d{8}-\d{5}[A-Z]$, so nothing issued can start being refused by it.
        assertThat(TicketNumbers.LEGACY.pattern()).isEqualTo("^\\d{8}-\\d{5}[A-Z]$");
        assertThat(TicketNumbers.isLegacy("41111111-11111X")).isTrue();
        assertThat(TicketNumbers.canonical("41111111-11111X")).isEmpty();
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

    // ---- What a log line may carry ----

    @Test
    void forLog_keepsTheDateAndTheLastThreeSymbols_ofACurrentNumber() {
        assertThat(TicketNumbers.forLog("20261013-7KQ4M-9XZ2B")).isEqualTo("20261013-*****-**Z2B");
    }

    @Test
    void forLog_keepsOnlyTheLastTwo_ofALegacyNumber() {
        // Five digits and a letter: showing three would leave a thousand a day.
        assertThat(TicketNumbers.forLog("20260419-48291X")).isEqualTo("20260419-****1X");
    }

    @Test
    void forLog_masksATypedSpelling_asTheNumberItIs() {
        assertThat(TicketNumbers.forLog("20261013 7kq4m 9xz2b")).isEqualTo("20261013-*****-**Z2B");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "20261013-7KQ4M-9XZ2O",          // one symbol off a real number
            "20261013-12345-67890",          // all digits: never issued
            "4111111111111111",
            "x\nINFO forged log line",
            "INN-20261013-ABC123"
    })
    void forLog_neverEchoesAValueThatIsNotATicketNumber(String value) {
        String logged = TicketNumbers.forLog(value);
        assertThat(logged).isEqualTo("(not a ticket number, " + value.length() + " chars)");
        assertThat(logged).doesNotContain("\n");
    }

    @Test
    void forLog_saysNoneForNullOrBlank() {
        assertThat(TicketNumbers.forLog(null)).isEqualTo("(none)");
        assertThat(TicketNumbers.forLog("  ")).isEqualTo("(none)");
    }

    @Test
    void redactIn_masksEveryTicketNumberInAPathOrABody() {
        assertThat(TicketNumbers.redactIn("/bookings/1f0e9c3a-7b2d-4e5f-9a1b-2c3d4e5f6a7b/tickets/20261013-7KQ4M-9XZ2B/qr.png"))
                .isEqualTo("/bookings/1f0e9c3a-7b2d-4e5f-9a1b-2c3d4e5f6a7b/tickets/20261013-*****-**Z2B/qr.png");
        assertThat(TicketNumbers.redactIn("{\"error\":\"bad qrCodePath /bookings/9/tickets/20260419-48291X/qr\"}"))
                .isEqualTo("{\"error\":\"bad qrCodePath /bookings/9/tickets/20260419-****1X/qr\"}");
        assertThat(TicketNumbers.redactIn("20261013-7KQ4M-9XZ2B and 20261013-ABCDE-FGH12"))
                .isEqualTo("20261013-*****-**Z2B and 20261013-*****-**H12");
    }

    @Test
    void redactIn_hidesNearMissesToo_andIsIdempotent() {
        // An O typed for a 0 is not a ticket number, but it is one symbol from one.
        assertThat(TicketNumbers.redactIn("t=20261013-7KQ4M-9XZ2O")).isEqualTo("t=20261013-*****-**Z2O");
        String once = TicketNumbers.redactIn("/t/20261013-7KQ4M-9XZ2B");
        assertThat(TicketNumbers.redactIn(once)).isEqualTo(once);
    }

    @Test
    void redactIn_leavesConfirmationNumbersUuidsAndLongerRunsAlone() {
        String text = "INN-20261013-ABC123 1f0e9c3a-7b2d-4e5f-9a1b-2c3d4e5f6a7b x20261013-7KQ4M-9XZ2B 20261013-7KQ4M-9XZ2BB";
        assertThat(TicketNumbers.redactIn(text)).isEqualTo(text);
        assertThat(TicketNumbers.redactIn(null)).isNull();
        assertThat(TicketNumbers.redactIn("")).isEmpty();
    }

    @Test
    void forDisplay_masksLikeTheLogs_andNeverShowsAValueThatIsNotATicket() {
        assertThat(TicketNumbers.forDisplay("20261030-CH2F0-5V7CB")).isEqualTo("20261030-*****-**7CB");
        assertThat(TicketNumbers.forDisplay("20261028-90100N")).isEqualTo("20261028-****0N");
        assertThat(TicketNumbers.forDisplay("TKT-SECRET")).isEqualTo("****");
        assertThat(TicketNumbers.forDisplay(null)).isNull();
    }
}
