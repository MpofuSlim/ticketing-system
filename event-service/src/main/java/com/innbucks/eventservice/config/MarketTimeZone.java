package com.innbucks.eventservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;

/**
 * The wall-clock timezone of the market this cell serves, resolved once from
 * {@code innbucks.country} (env {@code INNBUCKS_COUNTRY}).
 *
 * <p><b>Why this exists.</b> Event start/end are {@code LocalDateTime} columns —
 * zone-less — and {@code UtcJsonTimeConfig} serializes them with a literal
 * {@code Z}, i.e. the stored wall-clock IS asserted to be UTC. An organizer,
 * though, types the time the event actually starts *where it happens*: "07:00"
 * for a 7am fun run in Harare. Storing that verbatim claims 07:00 UTC, which is
 * 09:00 in Harare — the event reads two hours late everywhere downstream
 * (detail page, reminders, scan windows) and, worse, is a genuinely wrong
 * instant rather than a display quirk.
 *
 * <p>So the conversion belongs here, on the server: the client sends the time
 * the organizer typed, this class turns market-local into the UTC instant the
 * column is supposed to hold. Clients send wall-clock and do no timezone
 * arithmetic of their own.
 *
 * <p><b>Cell country is the right key.</b> A cell is pinned to one market
 * ({@link CountryMdcConfig} refuses to start otherwise) and {@code JwtFilter}
 * flags requests whose token carries a different country, so the cell's country
 * and an event's country are the same fact. Using the pin avoids depending on
 * the claim's spelling ("ZW" vs "Zimbabwe"). If a cell ever serves several
 * markets, switch the key to the event's own country — the conversion helpers
 * below take a zone, so only the caller changes.
 *
 * <p>None of the ten supported markets observes DST, so a local time is never
 * ambiguous or non-existent. That is why {@link #toUtc} can convert without a
 * gap/overlap policy. Revisit if a DST market is ever added.
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
            // Fail fast rather than defaulting to UTC. A silent UTC fallback
            // would store every event at the wrong instant for that market and
            // look completely healthy — the exact failure this class exists to
            // prevent. CountryMdcConfig already refuses unknown countries, so
            // reaching here means the two lists have drifted apart.
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
     * Read a market-local wall-clock as the UTC wall-clock our columns store.
     * {@code 07:00} in Harare becomes {@code 05:00}. Null passes through so
     * callers can hand over optional fields unguarded.
     */
    public LocalDateTime toUtc(LocalDateTime marketLocal) {
        return marketLocal == null ? null
                : marketLocal.atZone(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * The inverse — the market wall-clock for a stored UTC value. Not used on
     * the write path; here for callers that need to show or compare an event
     * time in the market's own clock (e.g. "is this event today, locally").
     */
    public LocalDateTime toMarketLocal(LocalDateTime utc) {
        return utc == null ? null
                : utc.atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDateTime();
    }
}
