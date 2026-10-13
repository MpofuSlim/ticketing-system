package com.innbucks.bookingservice.service;

import com.innbucks.common.ticket.TicketNumbers;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two references a booking carries (see {@link BookingReferences}): the
 * ticket number's shape, alphabet, UTC date and at-least-one-letter rule, the
 * unchanged confirmation-number shape, and that both come from the injected
 * randomness and clock.
 */
class BookingReferencesTest {

    // 23:30 UTC on the 13th is already the 14th in Harare: the date must be UTC's.
    private static final Clock LATE_EVENING_HARARE =
            Clock.fixed(Instant.parse("2026-10-13T23:30:00Z"), ZoneId.of("Africa/Harare"));

    @Test
    void aTicketNumber_isTheUtcDate_thenTwoGroupsOfFive_fromTheCrockfordAlphabet() {
        BookingReferences references = new BookingReferences(new java.security.SecureRandom(), LATE_EVENING_HARARE);

        for (int i = 0; i < 2_000; i++) {
            String ticket = references.nextTicketNumber();
            assertThat(ticket).hasSize(20).startsWith("20261013-").matches(TicketNumbers.CURRENT);
            assertThat(ticket.charAt(14)).isEqualTo('-');
            assertThat(ticket.substring(9).replace("-", "").chars())
                    .allMatch(c -> TicketNumbers.ALPHABET.indexOf(c) >= 0);
            assertThat(TicketNumbers.isTicketNumber(ticket)).isTrue();
        }
    }

    @Test
    void theSymbolsAreTheInjectedDraws_inOrder() {
        // 7 K Q 4 M | 9 X Z 2 B, as indices into the alphabet.
        ScriptedRandom random = new ScriptedRandom(7, 19, 23, 4, 20, 9, 29, 31, 2, 11);

        String ticket = new BookingReferences(random, LATE_EVENING_HARARE).nextTicketNumber();

        assertThat(ticket).isEqualTo("20261013-7KQ4M-9XZ2B");
        assertThat(random.bounds).hasSize(10).containsOnly(32);
    }

    @Test
    void aDrawOfTenDigits_isThrownAway_andDrawnAgain() {
        ScriptedRandom random = new ScriptedRandom(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 0,           // all digits: an 18-digit number once the dashes go
                7, 19, 23, 4, 20, 9, 29, 31, 2, 11);

        String ticket = new BookingReferences(random, LATE_EVENING_HARARE).nextTicketNumber();

        assertThat(ticket).isEqualTo("20261013-7KQ4M-9XZ2B");
        assertThat(random.bounds).hasSize(20);
    }

    @Test
    void aSingleLetterAnywhere_isEnough() {
        ScriptedRandom random = new ScriptedRandom(0, 0, 0, 0, 0, 0, 0, 0, 0, 31);

        assertThat(new BookingReferences(random, LATE_EVENING_HARARE).nextTicketNumber())
                .isEqualTo("20261013-00000-0000Z");
        assertThat(random.bounds).hasSize(10);
    }

    @Test
    void noTicketNumber_isEverAllDigits() {
        BookingReferences references = new BookingReferences();
        Pattern letter = Pattern.compile("[A-Z]");
        for (int i = 0; i < 2_000; i++) {
            assertThat(letter.matcher(references.nextTicketNumber().substring(9)).find()).isTrue();
        }
    }

    @Test
    void realDraws_spreadAcrossTheWholeAlphabet_andNeverRepeat() {
        // Not a test of a CSPRNG (nothing can be), but a regression to a
        // constant, a fixed seed or a narrowed alphabet collapses this.
        BookingReferences references = new BookingReferences();
        Set<String> seen = new HashSet<>();
        Set<Character> symbols = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            String ticket = references.nextTicketNumber();
            assertThat(seen.add(ticket)).as("repeat %s", ticket).isTrue();
            for (char c : ticket.substring(9).replace("-", "").toCharArray()) {
                symbols.add(c);
            }
        }
        assertThat(symbols).hasSize(32);
    }

    @Test
    void theDefaultConstructor_mintsTodaysUtcDate() {
        String before = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        String ticket = new BookingReferences().nextTicketNumber();
        String after = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        // Read either side of the mint, so a run straddling UTC midnight still passes.
        assertThat(ticket.substring(0, 8)).isIn(before, after);
    }

    @Test
    void aConfirmationNumber_keepsItsShape_fromTheInjectedDraw() {
        ScriptedRandom random = new ScriptedRandom(0x3FA9C0, 0, 0xFFFFFF);
        BookingReferences references = new BookingReferences(random, LATE_EVENING_HARARE);

        assertThat(references.nextConfirmationNumber()).isEqualTo("INN-20261013-3FA9C0");
        assertThat(references.nextConfirmationNumber()).isEqualTo("INN-20261013-000000");
        assertThat(references.nextConfirmationNumber()).isEqualTo("INN-20261013-FFFFFF");
        assertThat(random.bounds).containsOnly(1 << 24);
    }

    @Test
    void realConfirmationNumbers_matchTheGateLookupFormat() {
        BookingReferences references = new BookingReferences();
        for (int i = 0; i < 500; i++) {
            assertThat(references.nextConfirmationNumber()).matches("INN-\\d{8}-[0-9A-F]{6}");
        }
    }

    /** Returns scripted values from {@code nextInt(bound)}, recording each bound asked for. */
    static final class ScriptedRandom implements RandomGenerator {
        private final Deque<Integer> values = new ArrayDeque<>();
        final List<Integer> bounds = new ArrayList<>();

        ScriptedRandom(int... values) {
            for (int v : values) {
                this.values.add(v);
            }
        }

        @Override
        public int nextInt(int bound) {
            bounds.add(bound);
            Integer next = values.poll();
            if (next == null) {
                throw new AssertionError("ran out of scripted draws");
            }
            assertThat(next).isBetween(0, bound - 1);
            return next;
        }

        @Override
        public long nextLong() {
            throw new AssertionError("only nextInt(bound) is expected");
        }
    }
}
