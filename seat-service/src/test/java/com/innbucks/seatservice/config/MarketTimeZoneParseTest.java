package com.innbucks.seatservice.config;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MarketTimeZone#parseToUtc}: a zoneless value is what an organizer
 * typed in the market's clock; one with a zone is that instant, never shifted
 * twice.
 */
class MarketTimeZoneParseTest {

    private final MarketTimeZone harare = new MarketTimeZone("ZW");

    @Test
    void aZonelessValue_isTheMarketsLocalTime() {
        assertEquals(LocalDateTime.of(2026, 9, 8, 21, 59, 59), harare.parseToUtc("2026-09-08T23:59:59"));
        assertEquals(LocalDateTime.of(2026, 9, 8, 21, 0), harare.parseToUtc("2026-09-08T23:00"));
    }

    @Test
    void aZonelessValue_followsTheCellsMarket() {
        assertEquals(LocalDateTime.of(2026, 9, 8, 20, 59, 59),
                new MarketTimeZone("KE").parseToUtc("2026-09-08T23:59:59"));
    }

    @Test
    void aValueWithAZone_isThatExactInstant() {
        assertEquals(LocalDateTime.of(2026, 9, 8, 21, 59, 59), harare.parseToUtc("2026-09-08T21:59:59Z"));
        assertEquals(LocalDateTime.of(2026, 9, 8, 21, 59, 59), harare.parseToUtc("2026-09-08T23:59:59+02:00"));
        assertEquals(LocalDateTime.of(2026, 9, 8, 20, 59, 59), harare.parseToUtc("2026-09-08T23:59:59+03:00"));
        assertEquals(LocalDateTime.of(2026, 9, 8, 21, 59, 59), harare.parseToUtc(" 2026-09-08T21:59:59.000Z "));
    }

    @Test
    void anythingElse_isRefused() {
        assertThrows(DateTimeParseException.class, () -> harare.parseToUtc("2026-09-08"));
        assertThrows(DateTimeParseException.class, () -> harare.parseToUtc("8 Sept"));
        assertThrows(DateTimeParseException.class, () -> harare.parseToUtc(""));
        assertThrows(DateTimeParseException.class, () -> harare.parseToUtc(null));
    }
}
