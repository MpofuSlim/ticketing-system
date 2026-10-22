package com.innbucks.seatservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCategoryResponseDTO {

    private UUID seatCategoryId;
    private UUID eventId;
    private String name;
    private String description;

    /** The full ticket price — what an organizer edits. An early bird never changes it. */
    private BigDecimal price;

    /**
     * What one ticket costs if booked now: the early-bird price while it runs,
     * else {@code price}. booking-service charges this, so show it as the price.
     */
    private BigDecimal currentPrice;

    /** The category's early-bird offer, or null when it has none. */
    private EarlyBirdDTO earlyBird;

    /**
     * Live tickets still sellable in this category: {@code totalSeats} minus the
     * count of active (PENDING + CONFIRMED) bookings reported by booking-service.
     * Decrements as customers purchase and rebounds when holds expire or bookings
     * cancel. If booking-service is unreachable this falls back to the stored
     * mirror so the listing never fails.
     */
    private Integer availableSeats;

    private List<SectionSeatConfigDTO> sections;
}
