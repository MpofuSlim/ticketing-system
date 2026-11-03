package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.Booking;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Whether a public ticket page may still be opened by a record id
 * ({@code /bookings/public/{id}}, {@code /bookings/{bookingId}/tickets} and
 * its QR images, {@code /bookings/tickets/{itemId}} and its QR) rather than by
 * a link token ({@link LinkTokens}).
 *
 * <p>The ids are on staff screens, scan reports, support tools and logs, so
 * while those routes answer, anyone who can read an id can open the QR codes
 * it leads to. Every message we send carries token links from V31 on; the
 * switch decides how long the id links keep working:
 * <ul>
 *   <li>{@code open} (default): every booking, as before V31. The rollout
 *       state: clients still poll {@code /bookings/public/{id}} after
 *       checkout.</li>
 *   <li>{@code legacy}: only bookings created before
 *       {@code app.booking.id-links.legacy-before}, whose messages may have
 *       carried id links. Set it once every client polls by token, with the
 *       instant AFTER the V31 image finished rolling out.</li>
 *   <li>{@code closed}: none. Once the last event of a legacy booking has
 *       ended (an old link then opens a ticket nobody can use), and
 *       {@code booking.id_link{outcome="served"}} has gone flat.</li>
 * </ul>
 * A refused id answers exactly like an unknown one (404). The switch is a
 * rollout device: once every cell is {@code closed}, delete the id routes and
 * this class.
 */
@Slf4j
@Component
public class IdLinkPolicy {

    public enum Mode { OPEN, LEGACY, CLOSED }

    /** Every id link works, as before V31. For services built by hand in tests. */
    public static final IdLinkPolicy OPEN = new IdLinkPolicy(Mode.OPEN, null, null);

    private final Mode mode;
    private final LocalDateTime legacyBefore;
    private final Counter served;
    private final Counter refused;

    @Autowired
    public IdLinkPolicy(@Value("${app.booking.id-links.mode:open}") String mode,
                        @Value("${app.booking.id-links.legacy-before:}") String legacyBefore,
                        ObjectProvider<MeterRegistry> registry) {
        this(parseMode(mode), parseLegacyBefore(legacyBefore), registry.getIfAvailable());
        log.info("Public ticket pages by record id: mode={}{}", this.mode,
                this.mode == Mode.LEGACY ? " legacyBefore=" + this.legacyBefore + "Z" : "");
    }

    IdLinkPolicy(Mode mode, LocalDateTime legacyBefore, MeterRegistry registry) {
        if (mode == Mode.LEGACY && legacyBefore == null) {
            throw new IllegalStateException("app.booking.id-links.mode=legacy needs "
                    + "app.booking.id-links.legacy-before (BOOKINGS_ID_LINKS_LEGACY_BEFORE)");
        }
        this.mode = mode;
        this.legacyBefore = legacyBefore;
        this.served = registry == null ? null : Counter.builder("booking.id_link")
                .description("Public ticket pages asked for by record id rather than link token, by answer")
                .tag("outcome", "served").register(registry);
        this.refused = registry == null ? null : Counter.builder("booking.id_link")
                .description("Public ticket pages asked for by record id rather than link token, by answer")
                .tag("outcome", "refused").register(registry);
    }

    public Mode mode() {
        return mode;
    }

    /**
     * True when this booking's public pages may be opened by its id or one of
     * its items' ids. Counted either way.
     */
    public boolean allows(Booking booking) {
        boolean allowed = switch (mode) {
            case OPEN -> true;
            case LEGACY -> booking.getCreatedAt() != null && booking.getCreatedAt().isBefore(legacyBefore);
            case CLOSED -> false;
        };
        Counter counter = allowed ? served : refused;
        if (counter != null) {
            counter.increment();
        }
        return allowed;
    }

    private static Mode parseMode(String raw) {
        String value = raw == null || raw.isBlank() ? "open" : raw.trim();
        try {
            return Mode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.booking.id-links.mode must be open, legacy or closed, not '"
                    + value + "'", e);
        }
    }

    /** The cut-over instant, in the UTC wall clock {@code created_at} is stored in. */
    private static LocalDateTime parseLegacyBefore(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim()).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("app.booking.id-links.legacy-before must be an ISO-8601 instant "
                    + "with an offset, e.g. 2026-11-10T08:00:00Z, not '" + raw.trim() + "'", e);
        }
    }
}
