package com.innbucks.seatservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.seatservice.dto.PriceChangeDTO;
import com.innbucks.seatservice.entity.SeatCategoryPriceChange;
import com.innbucks.seatservice.entity.SeatCategoryPriceTier;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The tier snapshots a price change stores (V10), and their reading back.
 * Dates are stored as the UTC wall clock with no zone, the columns' own
 * convention, so the response renders them at the market offset like every
 * other date a person reads.
 */
@Slf4j
final class PriceHistory {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PriceHistory() {
    }

    /** One tier's terms as stored. */
    record Terms(String name, BigDecimal price, String startsAt, String endsAt, Integer quantity,
                 Integer maxPerCustomer, boolean refundsReopen) {
    }

    /** The tiers' terms as JSON, in order; "[]" for none. */
    static String snapshot(List<SeatCategoryPriceTier> tiers) {
        List<Terms> terms = tiers.stream()
                .map(t -> new Terms(t.getName(), t.getPrice(), text(t.getStartsAt()), text(t.getEndsAt()),
                        t.getQuantity(), t.getMaxPerCustomer(), t.isRefundsReopen()))
                .toList();
        try {
            return JSON.writeValueAsString(terms);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Price tier snapshot could not be written", e);
        }
    }

    static PriceChangeDTO toDto(SeatCategoryPriceChange change) {
        return PriceChangeDTO.builder()
                .id(change.getId())
                .changeType(change.getChangeType().name())
                .oldPrice(change.getOldPrice())
                .newPrice(change.getNewPrice())
                .tiersBefore(read(change.getTiersBefore()))
                .tiersAfter(read(change.getTiersAfter()))
                .changedBy(change.getChangedBy())
                .changedAt(change.getChangedAt())
                .build();
    }

    /** Null for no snapshot. An unreadable one is logged and read as none: history is never a 500. */
    private static List<PriceChangeDTO.Tier> read(String json) {
        if (json == null) {
            return null;
        }
        try {
            List<Terms> terms = JSON.readValue(json, new TypeReference<List<Terms>>() {});
            return terms.stream()
                    .map(t -> PriceChangeDTO.Tier.builder()
                            .name(t.name())
                            .price(t.price())
                            .startsAt(time(t.startsAt()))
                            .endsAt(time(t.endsAt()))
                            .quantity(t.quantity())
                            .maxPerCustomer(t.maxPerCustomer())
                            .refundsReopen(t.refundsReopen())
                            .build())
                    .toList();
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Price change tier snapshot unreadable, shown as none cause={}", e.toString());
            return null;
        }
    }

    private static String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private static LocalDateTime time(String value) {
        return value == null ? null : LocalDateTime.parse(value);
    }
}
