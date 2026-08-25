package com.innbucks.eventservice.config;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the market-local &lt;-&gt; UTC conversion that event start/end times go
 * through on the write path.
 *
 * <p>The concrete bug this guards: an organizer types 07:00 for a Harare event,
 * and without the conversion that wall-clock is stored verbatim and asserted to
 * be UTC — so the event reads 09:00 locally, two hours late, everywhere
 * downstream.
 */
class MarketTimeZoneTest {

    @Test
    void harareLocalIsStoredAsUtc_theSevenAmFunRunCase() {
        MarketTimeZone zw = new MarketTimeZone("ZW");

        LocalDateTime stored = zw.toUtc(LocalDateTime.of(2026, 9, 13, 7, 0));

        assertThat(stored)
                .as("07:00 in Harare is 05:00 UTC")
                .isEqualTo(LocalDateTime.of(2026, 9, 13, 5, 0));
    }

    @Test
    void conversionRoundTrips() {
        MarketTimeZone zw = new MarketTimeZone("ZW");
        LocalDateTime typed = LocalDateTime.of(2026, 9, 13, 7, 0);

        assertThat(zw.toMarketLocal(zw.toUtc(typed)))
                .as("what the organizer typed is what they get back")
                .isEqualTo(typed);
    }

    @Test
    void conversionCrossesTheDateBoundaryCorrectly() {
        // A 00:30 event in Harare is the PREVIOUS day at 22:30 UTC. Worth
        // pinning because a naive "subtract 2 hours from the time" would keep
        // the date and land the event a day out.
        MarketTimeZone zw = new MarketTimeZone("ZW");

        assertThat(zw.toUtc(LocalDateTime.of(2026, 9, 13, 0, 30)))
                .isEqualTo(LocalDateTime.of(2026, 9, 12, 22, 30));
    }

    @Test
    void eachMarketUsesItsOwnOffset_notAFlatTwoHours() {
        LocalDateTime nineAm = LocalDateTime.of(2026, 9, 13, 9, 0);

        // Nairobi is UTC+3 and Lagos UTC+1 — a hardcoded +2 would silently put
        // every Kenyan and Nigerian event an hour out.
        assertThat(new MarketTimeZone("KE").toUtc(nineAm)).isEqualTo(LocalDateTime.of(2026, 9, 13, 6, 0));
        assertThat(new MarketTimeZone("NG").toUtc(nineAm)).isEqualTo(LocalDateTime.of(2026, 9, 13, 8, 0));
        assertThat(new MarketTimeZone("ZA").toUtc(nineAm)).isEqualTo(LocalDateTime.of(2026, 9, 13, 7, 0));
    }

    @Test
    void countryCodeIsCaseAndWhitespaceInsensitive() {
        assertThat(new MarketTimeZone(" zw ").zone()).isEqualTo(ZoneId.of("Africa/Harare"));
    }

    @Test
    void nullsPassThrough_soOptionalFieldsNeedNoGuard() {
        MarketTimeZone zw = new MarketTimeZone("ZW");
        assertThat(zw.toUtc(null)).isNull();
        assertThat(zw.toMarketLocal(null)).isNull();
    }

    @Test
    void unknownCountryFailsFast_ratherThanSilentlyDefaultingToUtc() {
        // A UTC fallback would store every event at the wrong instant for that
        // market while looking perfectly healthy — the exact failure mode this
        // class exists to prevent, so it must not boot.
        assertThatThrownBy(() -> new MarketTimeZone("XX"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no market timezone mapping");
    }

    @Test
    void everyCountryTheCellPinAcceptsHasAZone() {
        // MarketTimeZone and CountryMdcConfig.KNOWN_COUNTRIES must stay in
        // lock-step: a country that boots the service but has no zone mapping
        // would fail at construction, taking the whole cell down.
        for (String country : new String[]{"ZW", "KE", "ZM", "MW", "ZA", "BW", "MZ", "LS", "SZ", "NG"}) {
            assertThat(new MarketTimeZone(country).zone())
                    .as("zone for %s", country)
                    .isNotNull();
        }
    }

    @Test
    void noSupportedMarketObservesDst_soLocalTimesAreNeverAmbiguous() {
        // toUtc() has no gap/overlap policy because it doesn't need one. If a
        // DST market is ever added this fails, which is the prompt to decide
        // what a 02:30 that happens twice should mean.
        LocalDateTime midSummer = LocalDateTime.of(2026, 1, 15, 2, 30);
        LocalDateTime midWinter = LocalDateTime.of(2026, 7, 15, 2, 30);
        for (String country : new String[]{"ZW", "KE", "ZM", "MW", "ZA", "BW", "MZ", "LS", "SZ", "NG"}) {
            ZoneId zone = new MarketTimeZone(country).zone();
            assertThat(zone.getRules().getOffset(midSummer))
                    .as("%s must have one fixed offset all year", country)
                    .isEqualTo(zone.getRules().getOffset(midWinter));
        }
    }
}
