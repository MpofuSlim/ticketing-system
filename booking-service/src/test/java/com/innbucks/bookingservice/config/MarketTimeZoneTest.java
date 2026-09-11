package com.innbucks.bookingservice.config;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.zone.ZoneRules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins booking-service's copy of the market clock.
 *
 * <p>Pure JUnit — the class is a plain constructor-injected component, so none
 * of this needs a Spring context or a Docker daemon.
 */
class MarketTimeZoneTest {

    @Test
    void rendersAnInstantAtTheMarketOffset_notUtc() {
        // The exact case from the operator's report: a scan made at 08:10 in
        // Harare is stored as 06:10Z. Rendered for the dashboard it must read
        // 08:10 — the clock the scanner was standing in — with the offset
        // spelled out so it is still an unambiguous instant.
        Instant stored = Instant.parse("2026-09-09T06:10:22Z");

        assertThat(new MarketTimeZone("ZW").atMarket(stored))
                .hasToString("2026-09-09T08:10:22+02:00");
    }

    @Test
    void marketsDoNotAllShareAnOffset() {
        // Guards against anyone "simplifying" this to a hardcoded +02:00.
        Instant stored = Instant.parse("2026-09-09T06:10:22Z");

        assertThat(new MarketTimeZone("KE").atMarket(stored))
                .hasToString("2026-09-09T09:10:22+03:00");
        assertThat(new MarketTimeZone("NG").atMarket(stored))
                .hasToString("2026-09-09T07:10:22+01:00");
    }

    @Test
    void endOfLocalDay_isTheLastInstantOfTheMARKETDay_notTheUtcDay() {
        // 2026-09-09T22:30Z is already the 10th in Harare (00:30 local), so the
        // end of ITS local day is the end of the 10th, not the 9th. Computing
        // this in UTC would land 2 hours early and silently drop the last two
        // hours of every day's scans.
        Instant lateEvening = Instant.parse("2026-09-09T22:30:00Z");

        assertThat(new MarketTimeZone("ZW").endOfLocalDay(lateEvening))
                .isEqualTo(Instant.parse("2026-09-10T21:59:59.999999999Z"));
    }

    @Test
    void endOfLocalDay_neverMovesTheBoundBackwards() {
        // The whole point is widening. A bound that moved earlier would hide
        // rows that used to be visible.
        MarketTimeZone zw = new MarketTimeZone("ZW");
        for (String t : new String[]{
                "2026-09-09T00:00:00Z", "2026-09-09T06:10:22Z",
                "2026-09-09T21:59:59Z", "2026-09-09T22:00:00Z",
                "2026-09-09T23:59:59Z"}) {
            Instant in = Instant.parse(t);
            assertThat(zw.endOfLocalDay(in))
                    .as("endOfLocalDay(%s) must not precede it", t)
                    .isAfterOrEqualTo(in);
        }
    }

    @Test
    void endOfLocalDay_staysInsideItsOwnDay_soHistoricalWindowsKeepTheirMeaning() {
        // A deliberately historical "up to the 3rd" must not start returning
        // the 4th onwards. This is what makes the widening safe to apply
        // unconditionally.
        Instant thirdMidday = Instant.parse("2026-09-03T10:00:00Z");
        Instant widened = new MarketTimeZone("ZW").endOfLocalDay(thirdMidday);

        assertThat(widened).isBefore(Instant.parse("2026-09-03T22:00:00Z"));
        assertThat(widened.atZone(ZoneId.of("Africa/Harare")).toLocalDate())
                .isEqualTo(thirdMidday.atZone(ZoneId.of("Africa/Harare")).toLocalDate());
    }

    @Test
    void nullsPassThrough() {
        MarketTimeZone zw = new MarketTimeZone("ZW");
        assertThat(zw.atMarket((java.time.Instant) null)).isNull();
        assertThat(zw.atMarketFromUtc(null)).isNull();
        assertThat(zw.endOfLocalDay(null)).isNull();
    }

    @Test
    void unknownCountryFailsFast_ratherThanSilentlyServingUtc() {
        // A UTC fallback would render every timestamp hours out while looking
        // perfectly healthy — the exact failure this class exists to prevent.
        assertThatThrownBy(() -> new MarketTimeZone("FR"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no market timezone mapping")
                .hasMessageContaining("CountryMdcConfig.KNOWN_COUNTRIES");
    }

    @Test
    void everyKnownCountryResolves_soTheTwoListsCannotDrift() {
        // Mirrors CountryMdcConfig.KNOWN_COUNTRIES. If a market is added there
        // and not here, the cell would refuse to boot — catch it at PR time.
        for (String iso : new String[]{"ZW", "KE", "ZM", "MW", "ZA", "BW", "MZ", "LS", "SZ", "NG"}) {
            assertThat(new MarketTimeZone(iso).zone()).as("country %s", iso).isNotNull();
        }
        assertThat(new MarketTimeZone("zw").zone()).isEqualTo(ZoneId.of("Africa/Harare"));
    }

    @Test
    void noSupportedMarketObservesDst_soLocalDayBoundariesAreNeverAmbiguous() {
        // endOfLocalDay picks LocalTime.MAX with no gap/overlap policy. That is
        // only safe while no market has DST — this fails the build if one is
        // ever added, rather than producing a silently wrong bound twice a year.
        //
        // Compares a summer and a winter offset rather than asking
        // isFixedOffset(): these zones DO carry historical transitions (LMT
        // changes from the 1900s), so isFixedOffset() is false for every one of
        // them even though none has observed DST in living memory. Same idiom
        // as event-service's MarketTimeZoneTest.
        LocalDateTime midSummer = LocalDateTime.of(2026, 1, 15, 2, 30);
        LocalDateTime midWinter = LocalDateTime.of(2026, 7, 15, 2, 30);
        for (String iso : new String[]{"ZW", "KE", "ZM", "MW", "ZA", "BW", "MZ", "LS", "SZ", "NG"}) {
            ZoneRules rules = new MarketTimeZone(iso).zone().getRules();
            assertThat(rules.getOffset(midSummer))
                    .as("%s must have one fixed offset all year", iso)
                    .isEqualTo(rules.getOffset(midWinter));
        }
    }
}
