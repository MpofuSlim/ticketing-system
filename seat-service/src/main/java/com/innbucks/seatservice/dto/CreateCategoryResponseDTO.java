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

    /** The full ticket price — what an organizer edits. A price tier never changes it. */
    private BigDecimal price;

    /**
     * What one ticket costs if booked now: the open price tier's price, else
     * {@code price}. Show it as the price. booking-service decides the price of
     * each ticket itself, by the same rule, at the moment of the booking.
     */
    private BigDecimal currentPrice;

    /** The open price tier's name, or null when the full price applies. */
    private String currentTierName;

    /** The category's price tiers in the order they apply; empty when it has none. */
    private List<PriceTierDTO> priceTiers;

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
