package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The tier rule a booking is charged by. */
class PriceTierAllocatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 8, 0);
    private final UUID early = UUID.randomUUID();
    private final UUID phase = UUID.randomUUID();

    /** VIP 150.00; Early bird 100.00 for 5 tickets until the given end; Phase 1 120.00 until 20 Sept. */
    private CategoryLookupDTO category(String earlyEnds) {
        return CategoryLookupDTO.builder().price(new BigDecimal("150.00")).priceTiers(List.of(
                        CategoryLookupDTO.PriceTier.builder().id(early).name("Early bird")
                                .price(new BigDecimal("100.00")).endsAt(earlyEnds).quantity(5).build(),
                        CategoryLookupDTO.PriceTier.builder().id(phase).name("Phase 1")
                                .price(new BigDecimal("120.00")).endsAt("2026-09-20T21:59:59Z").build()))
                .build();
    }

    private static List<BigDecimal> prices(List<PriceTierAllocator.TicketPrice> tickets) {
        return tickets.stream().map(PriceTierAllocator.TicketPrice::price).toList();
    }

    @Test
    void ticketsTakeTheFirstOpenTier_andSpillIntoTheNextAtItsQuantity() {
        List<PriceTierAllocator.TicketPrice> tickets = PriceTierAllocator.allocate(
                category("2026-09-08T21:59:59Z"), 3, Map.of(early, 3L), NOW);

        assertEquals(List.of(new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("120.00")),
                prices(tickets));
        assertEquals(List.of("Early bird", "Early bird", "Phase 1"),
                tickets.stream().map(PriceTierAllocator.TicketPrice::tierName).toList());
    }

    @Test
    void aTierPastItsEnd_isSkipped_andItsCountIsNeverAsked() {
        CategoryLookupDTO category = category("2026-09-01T08:00:00Z");

        assertTrue(PriceTierAllocator.tiersToCount(category, NOW).isEmpty());
        assertEquals(List.of(new BigDecimal("120.00")), prices(PriceTierAllocator.allocate(category, 1, Map.of(), NOW)));
    }

    @Test
    void afterEveryTier_theFullPriceApplies_withNoTier() {
        PriceTierAllocator.TicketPrice ticket = PriceTierAllocator.allocate(
                category("2026-08-01T00:00:00Z"), 1, Map.of(), LocalDateTime.of(2026, 9, 21, 0, 0)).get(0);

        assertEquals(new BigDecimal("150.00"), ticket.price());
        assertNull(ticket.tierId());
        assertNull(ticket.tierName());
    }

    @Test
    void onlyOpenQuantityLimitedTiers_areCounted() {
        assertEquals(List.of(early), PriceTierAllocator.tiersToCount(category("2026-09-08T21:59:59Z"), NOW));
    }

    @Test
    void anEndAtTheMarketOffset_isTheSameInstant() {
        // 23:59:59+02:00 is 21:59:59Z: at 22:00Z the tier is closed.
        CategoryLookupDTO category = category("2026-09-08T23:59:59+02:00");

        assertEquals(new BigDecimal("100.00"),
                PriceTierAllocator.allocate(category, 1, Map.of(), LocalDateTime.of(2026, 9, 8, 21, 59)).get(0).price());
        assertEquals(new BigDecimal("120.00"),
                PriceTierAllocator.allocate(category, 1, Map.of(), LocalDateTime.of(2026, 9, 8, 22, 0)).get(0).price());
    }

    @Test
    void anUnreadableEnd_closesTheTier_andATierNotBelowThePriceNeverApplies() {
        assertEquals(new BigDecimal("120.00"),
                PriceTierAllocator.allocate(category("next week"), 1, Map.of(), NOW).get(0).price());

        CategoryLookupDTO bad = CategoryLookupDTO.builder().price(new BigDecimal("150.00")).priceTiers(List.of(
                CategoryLookupDTO.PriceTier.builder().id(early).name("Bad").price(new BigDecimal("200.00"))
                        .quantity(5).build())).build();
        assertEquals(new BigDecimal("150.00"), PriceTierAllocator.allocate(bad, 1, Map.of(), NOW).get(0).price());
        assertTrue(PriceTierAllocator.tiersToCount(bad, NOW).isEmpty());
    }

    @Test
    void noTiers_isTheFullPrice() {
        CategoryLookupDTO plain = CategoryLookupDTO.builder().price(new BigDecimal("60.00")).build();

        assertEquals(List.of(new BigDecimal("60.00"), new BigDecimal("60.00")),
                prices(PriceTierAllocator.allocate(plain, 2, Map.of(), NOW)));
    }
}
