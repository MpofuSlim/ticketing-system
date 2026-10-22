package com.innbucks.seatservice.service;

import com.innbucks.seatservice.dto.PriceTierDTO;
import com.innbucks.seatservice.dto.PriceTierDTO.Status;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The tier rule every price seat-service shows comes from. */
class CategoryPricingTest {

    private static final BigDecimal FULL = new BigDecimal("150.00");
    private static final LocalDateTime EARLY_ENDS = LocalDateTime.of(2026, 9, 8, 21, 59, 59);
    private static final LocalDateTime PHASE_ENDS = LocalDateTime.of(2026, 9, 20, 21, 59, 59);

    private final UUID earlyId = UUID.randomUUID();
    private final UUID phaseId = UUID.randomUUID();

    /** Early bird: 100.00 for 100 tickets or until 8 Sept; Phase 1: 120.00 until 20 Sept. */
    private List<CategoryPricing.Tier> tiers() {
        return List.of(
                new CategoryPricing.Tier(earlyId, "Early bird", new BigDecimal("100.00"), EARLY_ENDS, 100),
                new CategoryPricing.Tier(phaseId, "Phase 1", new BigDecimal("120.00"), PHASE_ENDS, null));
    }

    private static List<Status> statuses(CategoryPricing.Priced priced) {
        return priced.tiers().stream().map(PriceTierDTO::getStatus).toList();
    }

    @Test
    void theFirstOpenTier_isThePrice() {
        CategoryPricing.Priced priced = CategoryPricing.price(FULL, tiers(), Map.of(earlyId, 37L),
                EARLY_ENDS.minusDays(1));

        assertEquals(new BigDecimal("100.00"), priced.currentPrice());
        assertEquals("Early bird", priced.currentTierName());
        assertEquals(List.of(Status.ACTIVE, Status.UPCOMING), statuses(priced));
        assertEquals(37L, priced.tiers().get(0).getSold());
        assertEquals(63L, priced.tiers().get(0).getRemaining());
        assertEquals(0L, priced.tiers().get(1).getSold());
        assertNull(priced.tiers().get(1).getRemaining());
    }

    @Test
    void aTierSoldToItsQuantity_handsOverToTheNext_beforeItsEnd() {
        CategoryPricing.Priced priced = CategoryPricing.price(FULL, tiers(), Map.of(earlyId, 100L),
                EARLY_ENDS.minusDays(1));

        assertEquals(new BigDecimal("120.00"), priced.currentPrice());
        assertEquals("Phase 1", priced.currentTierName());
        assertEquals(List.of(Status.SOLD_OUT, Status.ACTIVE), statuses(priced));
        assertEquals(0L, priced.tiers().get(0).getRemaining());
    }

    @Test
    void theEndIsExclusive_andAfterEveryTierTheFullPriceApplies() {
        assertEquals(new BigDecimal("120.00"),
                CategoryPricing.price(FULL, tiers(), Map.of(), EARLY_ENDS).currentPrice());

        CategoryPricing.Priced after = CategoryPricing.price(FULL, tiers(), Map.of(), PHASE_ENDS);
        assertEquals(FULL, after.currentPrice());
        assertNull(after.currentTierName());
        assertEquals(List.of(Status.ENDED, Status.ENDED), statuses(after));
    }

    @Test
    void unknownCounts_neverCloseATier_andAreLeftOut() {
        CategoryPricing.Priced priced = CategoryPricing.price(FULL, tiers(), null, EARLY_ENDS.minusDays(1));

        assertEquals(new BigDecimal("100.00"), priced.currentPrice());
        assertNull(priced.tiers().get(0).getSold());
        assertNull(priced.tiers().get(0).getRemaining());
    }

    @Test
    void noTiers_isTheFullPrice() {
        CategoryPricing.Priced priced = CategoryPricing.price(FULL, List.of(), Map.of(), EARLY_ENDS);

        assertEquals(FULL, priced.currentPrice());
        assertNull(priced.currentTierName());
        assertTrue(priced.tiers().isEmpty());
    }

    @Test
    void aTierNotBelowThePrice_neverRaisesWhatATicketCosts() {
        List<CategoryPricing.Tier> bad = List.of(
                new CategoryPricing.Tier(earlyId, "Bad", new BigDecimal("200.00"), EARLY_ENDS, null));

        assertEquals(FULL, CategoryPricing.price(FULL, bad, Map.of(), EARLY_ENDS.minusDays(1)).currentPrice());
    }

    @Test
    void theBookingRead_carriesTermsOnly() {
        List<PriceTierDTO> terms = CategoryPricing.terms(tiers());

        assertEquals(100, terms.get(0).getQuantity());
        assertEquals(EARLY_ENDS, terms.get(0).getEndsAt());
        assertNull(terms.get(0).getSold());
        assertNull(terms.get(0).getStatus());
    }
}
