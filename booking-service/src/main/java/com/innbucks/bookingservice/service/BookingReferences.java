package com.innbucks.bookingservice.service;

import com.innbucks.common.ticket.TicketNumbers;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Mints the two references a booking carries: one ticket number per ticket
 * and one confirmation number per booking. The only place either is made.
 *
 * <p><b>Ticket number</b> — {@code yyyyMMdd-XXXXX-XXXXX}, e.g.
 * {@code 20261013-7KQ4M-9XZ2B} (shape defined in {@link TicketNumbers}). The
 * date is the UTC day of issue; the ten symbols are drawn from
 * {@link TicketNumbers#ALPHABET}, Crockford base32, so 50 random bits per day.
 * The value IS the QR payload a gate scanner validates — an entry credential,
 * not a cosmetic id — so it comes from a CSPRNG: a {@code java.util.Random}
 * stream is reconstructable from one ticket, which would let its holder forge
 * a neighbour's.
 *
 * <p>Why 50 bits. {@code booking_items.ticket_number} is UNIQUE, so a clash
 * fails the checkout. The legacy shape ({@code yyyyMMdd-DDDDDL}, 2.6M values a
 * day) expected about 179 clashing pairs on a 30,505-seat stadium on-sale day,
 * and a guessed number for that stadium admitted with p ≈ 1%. At 50 bits,
 * 1M tickets in one day expect 4.4e-4 clashing pairs, and a guess for an event
 * with 100k valid tickets admits with p ≈ 1e-10. The claim still retries a
 * clash (see {@code BookingService.claimWithRetries}), but at this width a
 * ticket clash means this generator regressed — alert {@code TicketNumberCollision}.
 *
 * <p>Why at least one letter. A tail of ten digits would make the whole
 * number eighteen digits once the dashes are dropped: card-number DLP scanners
 * in mail and messaging flag that, and the support search refuses card-shaped
 * input. A draw whose ten symbols are all digits (p = (10/32)^10 ≈ 8.9e-6) is
 * thrown away and drawn again, which removes 1e10 of 1.1e15 values.
 *
 * <p><b>Confirmation number</b> — {@code INN-yyyyMMdd-} + six upper-case hex,
 * unchanged: people read it out and type it at the gate
 * ({@code GateLookupService}), so it stays short. 24 bits a day means clashes
 * are expected occasionally at stadium scale (about 27 pairs among 30k
 * bookings in a day); each one costs the claim a retry with fresh references,
 * never the customer's checkout.
 *
 * <p>Randomness and the clock are injectable so tests can script outputs; the
 * no-argument constructor (the one Spring uses) takes a {@link SecureRandom}
 * and the UTC system clock.
 */
@Component
public class BookingReferences {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final int ALPHABET_SIZE = TicketNumbers.ALPHABET.length();
    private static final int DIGITS = 10;
    private static final int CONFIRMATION_BOUND = 1 << 24;

    private final RandomGenerator random;
    private final Clock clock;

    public BookingReferences() {
        this(new SecureRandom(), Clock.systemUTC());
    }

    public BookingReferences(RandomGenerator random, Clock clock) {
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A fresh ticket number, e.g. {@code 20261013-7KQ4M-9XZ2B}. */
    public String nextTicketNumber() {
        int[] symbols = new int[TicketNumbers.RANDOM_SYMBOLS];
        do {
            for (int i = 0; i < symbols.length; i++) {
                symbols[i] = random.nextInt(ALPHABET_SIZE);
            }
        } while (allDigits(symbols));

        StringBuilder sb = new StringBuilder(9 + TicketNumbers.RANDOM_SYMBOLS + 1);
        sb.append(today()).append('-');
        for (int i = 0; i < symbols.length; i++) {
            if (i == TicketNumbers.GROUP_LENGTH) {
                sb.append('-');
            }
            sb.append(TicketNumbers.ALPHABET.charAt(symbols[i]));
        }
        return sb.toString();
    }

    /** A fresh confirmation number, e.g. {@code INN-20261013-3FA9C0}. */
    public String nextConfirmationNumber() {
        return "INN-" + today() + "-" + String.format(Locale.ROOT, "%06X", random.nextInt(CONFIRMATION_BOUND));
    }

    private String today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).format(DAY);
    }

    // The alphabet starts with the ten digits, so a symbol is a digit iff its index is below 10.
    private static boolean allDigits(int[] symbols) {
        for (int s : symbols) {
            if (s >= DIGITS) {
                return false;
            }
        }
        return true;
    }
}
