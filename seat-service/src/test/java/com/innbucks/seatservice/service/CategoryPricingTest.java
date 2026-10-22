package com.innbucks.seatservice.service;

import com.innbucks.seatservice.dto.EarlyBirdDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** The one early-bird rule every price on the wire comes from. */
class CategoryPricingTest {

    private static final BigDecimal FULL = new BigDecimal("150.00");
    private static final BigDecimal EARLY = new BigDecimal("100.00");
    private static final LocalDateTime ENDS = LocalDateTime.of(2026, 9, 8, 21, 59, 59);

    @Test
    void beforeTheEnd_theEarlyBirdPriceApplies() {
        LocalDateTime now = ENDS.minusSeconds(1);
        assertTrue(CategoryPricing.earlyBirdActive(FULL, EARLY, ENDS, now));
        assertEquals(EARLY, CategoryPricing.currentPrice(FULL, EARLY, ENDS, now));
    }

    @Test
    void theEndIsExclusive_andAfterItTheFullPriceApplies() {
        assertEquals(FULL, CategoryPricing.currentPrice(FULL, EARLY, ENDS, ENDS));
        assertEquals(FULL, CategoryPricing.currentPrice(FULL, EARLY, ENDS, ENDS.plusDays(1)));
        assertFalse(CategoryPricing.earlyBirdActive(FULL, EARLY, ENDS, ENDS));
    }

    @Test
    void noEarlyBird_isTheFullPrice_andNoOffer() {
        LocalDateTime now = ENDS.minusDays(1);
        assertEquals(FULL, CategoryPricing.currentPrice(FULL, null, null, now));
        assertNull(CategoryPricing.earlyBird(FULL, null, null, now));
    }

    @Test
    void anOfferNotBelowThePrice_neverRaisesWhatATicketCosts() {
        LocalDateTime now = ENDS.minusDays(1);
        assertEquals(FULL, CategoryPricing.currentPrice(FULL, FULL, ENDS, now));
        assertEquals(FULL, CategoryPricing.currentPrice(FULL, new BigDecimal("200.00"), ENDS, now));
        assertFalse(CategoryPricing.earlyBird(FULL, new BigDecimal("200.00"), ENDS, now).isActive());
    }

    @Test
    void anEndedOffer_isStillReadBack_asInactive() {
        EarlyBirdDTO running = CategoryPricing.earlyBird(FULL, EARLY, ENDS, ENDS.minusHours(1));
        EarlyBirdDTO ended = CategoryPricing.earlyBird(FULL, EARLY, ENDS, ENDS.plusHours(1));

        assertTrue(running.isActive());
        assertFalse(ended.isActive());
        assertEquals(EARLY, ended.getPrice());
        assertEquals(ENDS, ended.getEndsAt());
    }
}
