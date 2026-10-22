package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.PromoCode;
import com.innbucks.bookingservice.exception.ApiProblemException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The promo-code rules a booking and the public check apply. */
class PromoCodesTest {

    private static PromoCode code(PromoCode.DiscountType type, String value) {
        return PromoCode.builder().code("EARLY10").discountType(type).discountValue(new BigDecimal(value)).build();
    }

    @Test
    void aPercentage_isTakenOffEachTicket_roundedHalfUpToTheCent() {
        assertEquals(new BigDecimal("10.00"), PromoCodes.discountOn(code(PromoCode.DiscountType.PERCENT, "10"),
                new BigDecimal("100.00")));
        // 15% of 33.33 = 4.9995 -> 5.00
        assertEquals(new BigDecimal("5.00"), PromoCodes.discountOn(code(PromoCode.DiscountType.PERCENT, "15"),
                new BigDecimal("33.33")));
    }

    @Test
    void aFixedAmount_isTakenOffEachTicket_butNeverMakesOneFree() {
        assertEquals(new BigDecimal("20.00"), PromoCodes.discountOn(code(PromoCode.DiscountType.FIXED, "20"),
                new BigDecimal("100.00")));

        ApiProblemException free = assertThrows(ApiProblemException.class,
                () -> PromoCodes.discountOn(code(PromoCode.DiscountType.FIXED, "100"), new BigDecimal("100.00")));
        assertEquals(PromoCodes.TOO_LARGE, free.getErrorCode());
        assertThrows(ApiProblemException.class,
                () -> PromoCodes.discountOn(code(PromoCode.DiscountType.FIXED, "120"), new BigDecimal("100.00")));
    }

    @Test
    void aPercentageThatRoundsTheTicketToNothing_isRefusedToo() {
        // 99% of 0.01 rounds to 0.01: the ticket would be free.
        assertThrows(ApiProblemException.class,
                () -> PromoCodes.discountOn(code(PromoCode.DiscountType.PERCENT, "99"), new BigDecimal("0.01")));
    }

    @Test
    void noListedCategories_meansEveryCategory() {
        UUID vip = UUID.randomUUID();
        PromoCode all = code(PromoCode.DiscountType.PERCENT, "10");
        PromoCode vipOnly = code(PromoCode.DiscountType.PERCENT, "10");
        vipOnly.setCategoryIds(Set.of(vip));

        assertTrue(PromoCodes.appliesTo(all, UUID.randomUUID()));
        assertTrue(PromoCodes.appliesTo(vipOnly, vip));
        assertFalse(PromoCodes.appliesTo(vipOnly, UUID.randomUUID()));
    }

    @Test
    void theWindow_opensAtItsStart_andClosesAtItsEnd() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 8, 22, 0);
        PromoCode windowed = code(PromoCode.DiscountType.PERCENT, "10");
        windowed.setStartsAt(start);
        windowed.setEndsAt(end);

        assertEquals(PromoCodes.NOT_STARTED, assertThrows(ApiProblemException.class,
                () -> PromoCodes.requireOpen(windowed, start.minusSeconds(1))).getErrorCode());
        assertDoesNotThrow(() -> PromoCodes.requireOpen(windowed, start));
        assertDoesNotThrow(() -> PromoCodes.requireOpen(windowed, end.minusSeconds(1)));
        assertEquals(PromoCodes.EXPIRED, assertThrows(ApiProblemException.class,
                () -> PromoCodes.requireOpen(windowed, end)).getErrorCode());
    }

    @Test
    void aTypedCode_matchesInAnyCase() {
        assertEquals("EARLY10", PromoCodes.normalize("  early10 "));
        assertNull(PromoCodes.normalize("   "));
        assertNull(PromoCodes.normalize(null));
    }
}
