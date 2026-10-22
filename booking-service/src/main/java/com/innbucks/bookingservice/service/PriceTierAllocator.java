package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Prices each ticket of a booking from the category's price tiers (seat-service
 * V9): tiers apply in order, the first one still open takes the ticket, and
 * once every tier has closed the category's full price applies. A tier is open
 * until its {@code endsAt} (exclusive) and while fewer than its
 * {@code quantity} tickets are held or sold at it.
 *
 * <p>So a booking can straddle a boundary: three tickets with two left in
 * "Early bird" are two at the early-bird price and one at the next open tier
 * (or the full price). Each ticket records its own tier and price.
 *
 * <p>The counts come from {@code BookingService.persistBooking}, read AFTER
 * the category's {@code category_inventory} row is claimed in the same
 * transaction. Every booking, cancellation and expiry of the category takes
 * that row lock first, so no other transaction can add or release a ticket of
 * the category between the count and this booking's insert: "the first 100"
 * is exact under any concurrency.
 *
 * <p>seat-service's {@code CategoryPricing} and event-service's
 * {@code SeatCategoryGateway} apply the same rule for display. Change all
 * three together.
 */
@Slf4j
final class PriceTierAllocator {

    private PriceTierAllocator() {
    }

    /** One ticket's price, and the tier it was sold at (null at the full price). */
    record TicketPrice(UUID tierId, String tierName, BigDecimal price) {
    }

    /**
     * The tiers whose sold count decides this booking: open by time and
     * limited by quantity. Empty means no count query is needed at all.
     */
    static List<UUID> tiersToCount(CategoryLookupDTO category, LocalDateTime nowUtc) {
        List<UUID> ids = new ArrayList<>();
        for (CategoryLookupDTO.PriceTier tier : tiersOf(category)) {
            if (tier.getId() != null && tier.getQuantity() != null && openByTime(tier, nowUtc)
                    && belowFullPrice(tier, category.getPrice())) {
                ids.add(tier.getId());
            }
        }
        return ids;
    }

    /**
     * Price {@code quantity} tickets, in order, given what each tier has
     * already sold (a tier absent from {@code soldByTier} has sold nothing).
     */
    static List<TicketPrice> allocate(CategoryLookupDTO category, int quantity, Map<UUID, Long> soldByTier,
                                      LocalDateTime nowUtc) {
        List<TicketPrice> prices = new ArrayList<>(quantity);
        for (CategoryLookupDTO.PriceTier tier : tiersOf(category)) {
            if (prices.size() == quantity) {
                break;
            }
            if (tier.getId() == null || !openByTime(tier, nowUtc) || !belowFullPrice(tier, category.getPrice())) {
                continue;
            }
            long left = tier.getQuantity() == null
                    ? Long.MAX_VALUE
                    : tier.getQuantity() - soldByTier.getOrDefault(tier.getId(), 0L);
            while (left > 0 && prices.size() < quantity) {
                prices.add(new TicketPrice(tier.getId(), tier.getName(), tier.getPrice()));
                left--;
            }
        }
        while (prices.size() < quantity) {
            prices.add(new TicketPrice(null, null, category.getPrice()));
        }
        return prices;
    }

    private static List<CategoryLookupDTO.PriceTier> tiersOf(CategoryLookupDTO category) {
        return category.getPriceTiers() == null ? List.of() : category.getPriceTiers();
    }

    /**
     * Before its end, or no end. An end that cannot be read closes the tier:
     * a discount nobody can date is not given. seat-service always renders a
     * readable one, so this is a backstop.
     */
    private static boolean openByTime(CategoryLookupDTO.PriceTier tier, LocalDateTime nowUtc) {
        if (tier.getEndsAt() == null || tier.getEndsAt().isBlank()) {
            return true;
        }
        LocalDateTime endsAtUtc = parseUtc(tier.getEndsAt());
        if (endsAtUtc == null) {
            log.warn("Price tier end unreadable, tier treated as closed tierId={}", tier.getId());
            return false;
        }
        return nowUtc.isBefore(endsAtUtc);
    }

    /** Never sell a "tier" at or above the full price: a bad row must not raise what a ticket costs. */
    private static boolean belowFullPrice(CategoryLookupDTO.PriceTier tier, BigDecimal fullPrice) {
        return tier.getPrice() != null && fullPrice != null && tier.getPrice().compareTo(fullPrice) < 0;
    }

    /** With a zone, that instant; without one, UTC (the S2S convention). Null when unreadable. */
    static LocalDateTime parseUtc(String value) {
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException withoutZone) {
            try {
                return LocalDateTime.parse(text);
            } catch (DateTimeParseException unreadable) {
                return null;
            }
        }
    }
}
