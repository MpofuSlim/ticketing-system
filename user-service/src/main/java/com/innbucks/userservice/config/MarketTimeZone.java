package com.innbucks.userservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;

/**
 * The wall-clock timezone of the market this cell serves, resolved once from
 * {@code innbucks.country} (env {@code INNBUCKS_COUNTRY}).
 *
 * <p>Mirrors the classes of the same name in booking-service and event-service;
 * this copy exists because the services share no module. All of them must stay
 * in lock-step with {@link CountryMdcConfig}'s known-country list — an unmapped
 * country throws at construction (taking the cell down) rather than defaulting
 * to UTC, because a silent UTC fallback would render every timestamp two hours
 * out while looking perfectly healthy.
 *
 * <p><b>Why user-service needs it.</b> CLAUDE.md's wire-format rule: the BE renders
 * and the FE parses nothing, so every timestamp a person reads leaves here at
 * the market offset rather than as {@code Z}. {@link UtcJsonTimeConfig} calls
 * {@link #atMarketFromUtc} on the response path for exactly that. Storage is
 * untouched — columns, queries and comparisons stay UTC.
 *
 * <p>None of the ten supported markets observes DST, so a local time is never
 * ambiguous or non-existent. Revisit if a DST market is ever added.
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
                            + "lock-step with CountryMdcConfig's known-country list)");
        }
        this.zone = resolved;
    }

    /** This cell's market timezone. */
    public ZoneId zone() {
        return zone;
    }

    /**
     * Render a stored UTC wall-clock at the market offset for the wire.
     *
     * <p>Our {@code LocalDateTime} columns carry no zone but MEAN UTC (see
     * CLAUDE.md), so this reads the value as UTC and re-expresses the same
     * instant in the market's clock: {@code 06:10:22} stored becomes
     * {@code 08:10:22+02:00} for a ZW cell. The instant is unchanged — only the
     * digits a person reads. Null passes through so callers can hand over
     * optional fields unguarded.
     */
    public OffsetDateTime atMarketFromUtc(LocalDateTime utcWallClock) {
        return utcWallClock == null ? null
                : utcWallClock.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toOffsetDateTime();
    }
}
