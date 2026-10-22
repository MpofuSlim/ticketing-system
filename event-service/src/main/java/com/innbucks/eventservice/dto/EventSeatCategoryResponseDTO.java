package com.innbucks.eventservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSeatCategoryResponseDTO {
    private String name;
    private String description;
    /** The category's full price. */
    private BigDecimal categoryPrice;
    /**
     * What one ticket costs if booked now: the early-bird price while it runs,
     * else {@code categoryPrice}. booking-service charges the same.
     */
    private BigDecimal currentPrice;
    /** The category's early-bird offer, or null when it has none. */
    private EventEarlyBirdDTO earlyBird;
    private List<EventSectionResponseDTO> sections;
}
