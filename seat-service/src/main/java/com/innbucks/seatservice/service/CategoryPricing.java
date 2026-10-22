package com.innbucks.seatservice.service;

import com.innbucks.seatservice.dto.PriceTierDTO;
import com.innbucks.seatservice.entity.SeatCategoryPriceTier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which price a seat category charges at a given moment. Tiers apply in order
 * and the first one open is the price; once every tier has closed, the
 * category's own price. A tier is open from {@code startsAt} (inclusive) until
 * {@code endsAt} (exclusive) and until {@code quantity} tickets are held or
 * sold at it (refunded ones too, when it does not reopen on refund). Its
 * per-customer cap only moves one buyer's extra tickets to the next tier, so
 * it never changes what the listing shows.
 *
 * <p>The one place seat-service applies the rule, so every {@code currentPrice}
 * and tier {@code status} it shows comes from here. booking-service applies the
 * same rule to price each ticket (its {@code PriceTierAllocator}), counting the
 * tickets sold at each tier under the category's stock row lock; event-service
 * copies it for the event page. Change all three together.
 *
 * <p>Worked out per request, never stored or cached, so a tier closes on time
 * on every replica whatever a cache holds: callers pass the time of the
 * request, not the time a layout was cached.
 */
public final class CategoryPricing {

    private CategoryPricing() {
    }

    /** A tier's terms, as stored or cached. Immutable. */
    public record Tier(UUID id, String name, BigDecimal price, LocalDateTime startsAtUtc, LocalDateTime endsAtUtc,
                       Integer quantity, Integer maxPerCustomer, boolean refundsReopen) {

        /** Terms with no start, no per-customer cap, and refunds reopening. */
        public Tier(UUID id, String name, BigDecimal price, LocalDateTime endsAtUtc, Integer quantity) {
            this(id, name, price, null, endsAtUtc, quantity, null, true);
        }

        static Tier of(SeatCategoryPriceTier tier) {
            return new Tier(tier.getId(), tier.getName(), tier.getPrice(), tier.getStartsAt(), tier.getEndsAt(),
                    tier.getQuantity(), tier.getMaxPerCustomer(), tier.isRefundsReopen());
        }

        boolean ended(LocalDateTime nowUtc) {
            return endsAtUtc != null && !nowUtc.isBefore(endsAtUtc);
        }

        boolean notStarted(LocalDateTime nowUtc) {
            return startsAtUtc != null && nowUtc.isBefore(startsAtUtc);
        }

        boolean soldOut(Long sold) {
            return quantity != null && sold != null && sold >= quantity;
        }

        /** What counts against {@link #quantity}: held and sold, plus refunded unless refunds reopen. */
        Long counted(Sold sold) {
            return sold == null ? null : sold.active() + (refundsReopen ? 0 : sold.refunded());
        }
    }

    /** One tier's tickets as booking-service counts them: held or sold, and refunded after payment. */
    public record Sold(long active, long refunded) {
        static final Sold NONE = new Sold(0, 0);
    }

    /** One category priced at one moment. */
    public record Priced(BigDecimal currentPrice, String currentTierName, List<PriceTierDTO> tiers) {
    }

    /**
     * Price a category at {@code nowUtc}.
     *
     * @param soldByTier each tier's tickets, or null when booking-service could
     *                   not be asked. Unknown counts never close a tier here
     *                   (booking-service still counts exactly) and leave
     *                   {@code sold}/{@code remaining} out.
     */
    public static Priced price(BigDecimal fullPrice, List<Tier> tiers, Map<UUID, Sold> soldByTier,
                               LocalDateTime nowUtc) {
        List<PriceTierDTO> out = new ArrayList<>(tiers.size());
        Tier current = null;
        for (Tier tier : tiers) {
            Long sold = soldByTier == null ? null : tier.counted(soldByTier.getOrDefault(tier.id(), Sold.NONE));
            PriceTierDTO.Status status;
            if (tier.ended(nowUtc)) {
                status = PriceTierDTO.Status.ENDED;
            } else if (tier.soldOut(sold)) {
                status = PriceTierDTO.Status.SOLD_OUT;
            } else if (tier.notStarted(nowUtc)) {
                status = PriceTierDTO.Status.UPCOMING;
            } else if (current == null && belowFullPrice(tier, fullPrice)) {
                status = PriceTierDTO.Status.ACTIVE;
                current = tier;
            } else {
                status = PriceTierDTO.Status.UPCOMING;
            }
            out.add(PriceTierDTO.builder()
                    .id(tier.id())
                    .name(tier.name())
                    .price(tier.price())
                    .startsAt(tier.startsAtUtc())
                    .endsAt(tier.endsAtUtc())
                    .quantity(tier.quantity())
                    .maxPerCustomer(tier.maxPerCustomer())
                    .refundsReopen(tier.refundsReopen())
                    .sold(sold)
                    .remaining(tier.quantity() == null || sold == null ? null
                            : Math.max(0L, tier.quantity() - sold))
                    .status(status)
                    .build());
        }
        return new Priced(current == null ? fullPrice : current.price(),
                current == null ? null : current.name(), out);
    }

    /**
     * What {@code unsold} tickets would bring in if they all sold at
     * {@code nowUtc}: the tiers open now fill in order up to what each has
     * left (a tier without a quantity takes the rest), then the full price.
     * A per-customer cap never limits the total, so it is ignored. Null when a
     * quantity-limited tier is open and its count is unknown
     * ({@code soldByTier} null), or the full price is unknown.
     */
    public static BigDecimal valueIfSoldNow(BigDecimal fullPrice, List<Tier> tiers, Map<UUID, Sold> soldByTier,
                                            long unsold, LocalDateTime nowUtc) {
        if (fullPrice == null) {
            return null;
        }
        long left = Math.max(0L, unsold);
        BigDecimal value = BigDecimal.ZERO;
        for (Tier tier : tiers) {
            if (left == 0) {
                break;
            }
            if (tier.ended(nowUtc) || tier.notStarted(nowUtc) || !belowFullPrice(tier, fullPrice)) {
                continue;
            }
            long room = left;
            if (tier.quantity() != null) {
                if (soldByTier == null) {
                    return null;
                }
                room = Math.max(0L, tier.quantity() - tier.counted(soldByTier.getOrDefault(tier.id(), Sold.NONE)));
            }
            long take = Math.min(room, left);
            value = value.add(tier.price().multiply(BigDecimal.valueOf(take)));
            left -= take;
        }
        return value.add(fullPrice.multiply(BigDecimal.valueOf(left)));
    }

    /** The tiers' terms alone, for the booking read: no counts, no status. */
    public static List<PriceTierDTO> terms(List<Tier> tiers) {
        return tiers.stream()
                .map(tier -> PriceTierDTO.builder()
                        .id(tier.id())
                        .name(tier.name())
                        .price(tier.price())
                        .startsAt(tier.startsAtUtc())
                        .endsAt(tier.endsAtUtc())
                        .quantity(tier.quantity())
                        .maxPerCustomer(tier.maxPerCustomer())
                        .refundsReopen(tier.refundsReopen())
                        .build())
                .toList();
    }

    /**
     * A backstop: the service refuses a tier that is not below the full price,
     * but a bad row must still never raise what a ticket costs.
     */
    private static boolean belowFullPrice(Tier tier, BigDecimal fullPrice) {
        return fullPrice != null && tier.price() != null && tier.price().compareTo(fullPrice) < 0;
    }
}
