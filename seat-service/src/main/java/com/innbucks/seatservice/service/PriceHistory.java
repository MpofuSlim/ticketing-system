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
 * The tier snapshots a price change stores (V11), and their reading back.
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

    /**
     * What an organizer reads in {@code changedBy} for a change someone else
     * made. Only the event's organizer and SUPER_ADMIN can change a price, so
     * that someone is InnBucks staff, whose JWT email is how a staff account is
     * found: it goes to staff readers only.
     */
    static final String CHANGED_BY_INNBUCKS = "InnBucks";

    /**
     * {@code eventOwner} is the event's organizer: for an organizer reader
     * themselves (the ownership check ran before this), for a staff reader
     * whatever event-service answered, null when it could not.
     * {@code staffReader} sees who made every change.
     */
    static PriceChangeDTO toDto(SeatCategoryPriceChange change, java.util.UUID eventOwner, boolean staffReader) {
        String stored = change.getChangedBy();
        Boolean byOrganizer = byOrganizer(stored, eventOwner);
        return PriceChangeDTO.builder()
                .id(change.getId())
                .changeType(change.getChangeType().name())
                .oldPrice(change.getOldPrice())
                .newPrice(change.getNewPrice())
                .tiersBefore(read(change.getTiersBefore()))
                .tiersAfter(read(change.getTiersAfter()))
                .changedBy(stored == null || staffReader || Boolean.TRUE.equals(byOrganizer)
                        ? stored : CHANGED_BY_INNBUCKS)
                .changedByOrganizer(byOrganizer)
                .changedAt(change.getChangedAt())
                .build();
    }

    /**
     * Whether the event's organizer made the change: the auditor stamps an
     * organizer's own uuid. Null when it cannot be said: no author was
     * recorded, or the owner is unknown (a staff reader whose event lookup
     * failed). An organizer reader always has an owner, so for them it is null
     * only for a change with no recorded author.
     */
    private static Boolean byOrganizer(String stored, java.util.UUID eventOwner) {
        if (stored == null || eventOwner == null) {
            return null;
        }
        return eventOwner.toString().equalsIgnoreCase(stored);
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
