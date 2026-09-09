package com.innbucks.bookingservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

/**
 * The wall-clock timezone of the market this cell serves, resolved once from
 * {@code innbucks.country} (env {@code INNBUCKS_COUNTRY}).
 *
 * <p>Mirrors {@code event-service}'s class of the same name; this copy exists
 * because booking-service needs the market clock for the scan-report surface
 * and the two services share no module. Both must stay in lock-step with
 * {@link CountryMdcConfig#KNOWN_COUNTRIES} — an unmapped country throws at
 * construction (taking the cell down) rather than defaulting to UTC, because a
 * silent UTC fallback would render every timestamp two hours out while looking
 * perfectly healthy.
 *
 * <p><b>Why booking-service needs it.</b> {@code scan_attempts.attempted_at} is
 * a true UTC {@link Instant} and stays that way at rest — that is not
 * negotiable. But the gate-staff dashboard is a single-market operator tool,
 * and the operator reads the raw string we send. Serving {@code 06:10:22Z} for
 * a scan made at 08:10 in Harare is correct-but-unreadable: the reader has to
 * do the arithmetic. So the scan-report surface renders the same instant at the
 * market offset ({@code 2026-09-09T08:10:22+02:00}), which is exactly as
 * unambiguous as {@code Z} and needs no client-side conversion.
 *
 * <p>None of the ten supported markets observes DST, so a local time is never
 * ambiguous or non-existent and {@link #endOfLocalDay} needs no gap/overlap
 * policy. Revisit if a DST market is ever added.
 */
@Component
public class MarketTimeZone {

    /**
     * Market -> IANA zone, covering exactly the countries
     * {@link CountryMdcConfig} accepts. Kept as a plain map rather than derived
     * from a locale library so the offsets are reviewable in one place.
     */
    private static final Map<String, ZoneId> ZONES = Map.ofEntries(
            Map.entry("ZW", ZoneId.of("Africa/Harare")),        // UTC+2
            Map.entry("KE", ZoneId.of("Africa/Nairobi")),       // UTC+3
            Map.entry("ZM", ZoneId.of("Africa/Lusaka")),        // UTC+2
            Map.entry("MW", ZoneId.of("Africa/Blantyre")),      // UTC+2
            Map.entry("ZA", ZoneId.of("Africa/Johannesburg")),  // UTC+2
            Map.entry("BW", ZoneId.of("Africa/Gaborone")),      // UTC+2
            Map.entry("MZ", ZoneId.of("Africa/Maputo")),        // UTC+2
            Map.entry("LS", ZoneId.of("Africa/Maseru")),        // UTC+2
            Map.entry("SZ", ZoneId.of("Africa/Mbabane")),       // UTC+2
            Map.entry("NG", ZoneId.of("Africa/Lagos"))          // UTC+1
    );

    private final ZoneId zone;

    public MarketTimeZone(@Value("${innbucks.country:ZW}") String country) {
        String key = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        ZoneId resolved = ZONES.get(key);
        if (resolved == null) {
            throw new IllegalStateException(
                    "innbucks.country='" + country + "' has no market timezone mapping. Known: "
                            + ZONES.keySet() + " — add it to MarketTimeZone (it must stay in "
                            + "lock-step with CountryMdcConfig.KNOWN_COUNTRIES)");
        }
        this.zone = resolved;
    }

    /** This cell's market timezone. */
    public ZoneId zone() {
        return zone;
    }

    /**
     * The same instant, rendered at the market's offset — the form the
     * scan-report surface puts on the wire. Null passes through so callers can
     * map optional fields unguarded.
     *
     * <p>{@code 2026-09-09T06:10:22Z} becomes {@code 2026-09-09T08:10:22+02:00}
     * for a ZW cell: identical instant, but the leading characters are the
     * clock the operator was actually looking at when they scanned.
     */
    public OffsetDateTime atMarket(Instant instant) {
        return instant == null ? null : instant.atZone(zone).toOffsetDateTime();
    }

    /**
     * The last nanosecond of the market-local calendar day that contains
     * {@code instant}.
     *
     * <p>Used to widen a report's upper bound. A client that builds "now" once
     * and then re-sends it forever pins the window to the moment its screen was
     * opened, so every later scan falls outside a closed {@code BETWEEN} and
     * simply never appears. Rounding the bound up to the end of its own local
     * day fixes that for the rest of the working day without guessing at
     * intent: a scan can never be recorded in the future
     * ({@code attemptedAt = Instant.now()} at scan time), so widening to the
     * end of a day can only admit rows that have genuinely already happened.
     * A bound on a past day still ends on that past day, so a deliberately
     * historical window keeps its meaning.
     */
    public Instant endOfLocalDay(Instant instant) {
        return instant == null ? null
                : instant.atZone(zone).toLocalDate().atTime(LocalTime.MAX).atZone(zone).toInstant();
    }
}
