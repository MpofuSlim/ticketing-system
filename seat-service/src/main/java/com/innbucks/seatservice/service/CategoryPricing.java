package com.innbucks.seatservice.service;

import com.innbucks.seatservice.dto.EarlyBirdDTO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Which price a seat category charges at a given moment. The one place the
 * early-bird rule lives: every response's {@code currentPrice}, and so what
 * booking-service charges, comes from here.
 *
 * <p>Worked out at read time, never stored, so nothing has to run when an early
 * bird ends and a cached layout can never serve a price past its end: callers
 * pass the time of the request, not the time the layout was cached.
 */
public final class CategoryPricing {

    private CategoryPricing() {
    }

    /**
     * True while the early-bird price applies: one is set, {@code nowUtc} is
     * before its end, and it is below the full price. The last test is a
     * backstop: the service refuses an early bird that is not lower, but a
     * misconfigured row must still never raise what a ticket costs.
     */
    public static boolean earlyBirdActive(BigDecimal price, BigDecimal earlyBirdPrice,
                                          LocalDateTime earlyBirdEndsAtUtc, LocalDateTime nowUtc) {
        return price != null && earlyBirdPrice != null && earlyBirdEndsAtUtc != null
                && nowUtc.isBefore(earlyBirdEndsAtUtc)
                && earlyBirdPrice.compareTo(price) < 0;
    }

    /** What one ticket costs if booked at {@code nowUtc}. */
    public static BigDecimal currentPrice(BigDecimal price, BigDecimal earlyBirdPrice,
                                          LocalDateTime earlyBirdEndsAtUtc, LocalDateTime nowUtc) {
        return earlyBirdActive(price, earlyBirdPrice, earlyBirdEndsAtUtc, nowUtc) ? earlyBirdPrice : price;
    }

    /** The offer as read back, or null when the category has none. */
    public static EarlyBirdDTO earlyBird(BigDecimal price, BigDecimal earlyBirdPrice,
                                         LocalDateTime earlyBirdEndsAtUtc, LocalDateTime nowUtc) {
        if (earlyBirdPrice == null || earlyBirdEndsAtUtc == null) {
            return null;
        }
        return EarlyBirdDTO.builder()
                .price(earlyBirdPrice)
                .endsAt(earlyBirdEndsAtUtc)
                .active(earlyBirdActive(price, earlyBirdPrice, earlyBirdEndsAtUtc, nowUtc))
                .build();
    }
}
